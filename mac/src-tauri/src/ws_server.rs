use crate::pairing::PairingManager;
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, Mutex};
use tokio_tungstenite::tungstenite::http;
use tokio_tungstenite::tungstenite::Message;

/// 剪贴板数据消息
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipMessage {
    #[serde(default = "default_clip_type")]
    #[serde(rename = "type")]
    pub msg_type: String,
    pub device_id: String,
    pub text: String,
    pub hash: String,
    pub timestamp: u64,
}

fn default_clip_type() -> String {
    "clip".to_string()
}

/// 协议消息类型（包含配对相关消息）
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum ProtocolMessage {
    /// 剪贴板数据
    #[serde(rename = "clip")]
    Clip {
        device_id: String,
        text: String,
        hash: String,
        timestamp: u64,
    },
    /// 配对成功通知（Mac → Android）
    #[serde(rename = "pair_ok")]
    PairOk {
        device: String,
    },
    /// 设备被踢通知（Mac → 旧 Android）
    #[serde(rename = "kicked")]
    Kicked {
        reason: String,
    },
    /// 解除配对通知（双向）
    #[serde(rename = "unpair")]
    Unpair {},
}

/// WebSocket 连接事件
#[derive(Debug, Clone)]
pub enum WsConnectionEvent {
    /// 客户端已连接（配对成功）
    Connected,
    /// 客户端已断开
    Disconnected,
}

/// 已配对客户端信息
#[derive(Debug)]
struct PairedClient {
    /// 客户端地址
    addr: SocketAddr,
    /// 客户端 device_id（首条 clip 消息确认）
    device_id: String,
    /// 向该客户端发送消息的通道
    sender: mpsc::Sender<String>,
    /// 中止该连接的 handle
    abort_handle: tokio::task::JoinHandle<()>,
}

/// WebSocket 服务器（带 token 鉴权和 1:1 配对）
pub struct WsServer {
    /// 绑定的端口
    port: u16,
    /// 配对管理器
    pairing: Arc<PairingManager>,
    /// 当前已配对的客户端（1:1 模型，最多一个）
    paired_client: Arc<Mutex<Option<PairedClient>>>,
    /// 接收来自客户端的剪贴板消息
    incoming_tx: mpsc::Sender<ClipMessage>,
    /// 连接事件通知
    conn_event_tx: mpsc::Sender<WsConnectionEvent>,
}

impl WsServer {
    pub fn new(
        port: u16,
        pairing: Arc<PairingManager>,
        incoming_tx: mpsc::Sender<ClipMessage>,
        conn_event_tx: mpsc::Sender<WsConnectionEvent>,
    ) -> Self {
        Self {
            port,
            pairing,
            paired_client: Arc::new(Mutex::new(None)),
            incoming_tx,
            conn_event_tx,
        }
    }

    /// 启动 WebSocket 服务器，返回 (实际端口, 监听任务 Handle)
    pub async fn start(&self) -> Result<(u16, tokio::task::JoinHandle<()>), String> {
        let addr = format!("0.0.0.0:{}", self.port);
        let listener = TcpListener::bind(&addr)
            .await
            .map_err(|e| format!("WebSocket 绑定 {} 失败: {}", addr, e))?;

        let local_port = listener
            .local_addr()
            .map_err(|e| format!("获取本地地址失败: {}", e))?
            .port();

        crate::debug_log(&format!("WebSocket Server 已启动: 0.0.0.0:{}", local_port));

        let pairing = self.pairing.clone();
        let paired_client = self.paired_client.clone();
        let incoming_tx = self.incoming_tx.clone();
        let conn_event_tx = self.conn_event_tx.clone();

        let handle = tokio::spawn(async move {
            loop {
                match listener.accept().await {
                    Ok((stream, addr)) => {
                        let pairing = pairing.clone();
                        let paired_client = paired_client.clone();
                        let incoming_tx = incoming_tx.clone();
                        let conn_event_tx = conn_event_tx.clone();
                        tokio::spawn(async move {
                            handle_new_connection(
                                stream,
                                addr,
                                pairing,
                                paired_client,
                                incoming_tx,
                                conn_event_tx,
                            )
                            .await;
                        });
                    }
                    Err(e) => {
                        log::error!("WebSocket 接受连接失败: {}", e);
                    }
                }
            }
        });

        Ok((local_port, handle))
    }

    /// 向已配对客户端发送剪贴板消息
    pub fn broadcast(&self, msg: &ClipMessage) -> Result<(), String> {
        let json = serde_json::to_string(msg).map_err(|e| format!("序列化失败: {}", e))?;
        let paired_client = self.paired_client.clone();
        let json_clone = json.clone();
        tokio::spawn(async move {
            let guard = paired_client.lock().await;
            if let Some(ref client) = *guard {
                if client.sender.send(json_clone).await.is_err() {
                    log::warn!("向已配对客户端发送消息失败");
                }
            }
        });
        Ok(())
    }

    /// 获取当前已连接的客户端数量（0 或 1）
    pub async fn client_count(&self) -> usize {
        let guard = self.paired_client.lock().await;
        if guard.is_some() { 1 } else { 0 }
    }

    /// 向已配对客户端发送解除配对消息并断开
    pub async fn send_unpair_and_disconnect(&self) {
        let mut guard = self.paired_client.lock().await;
        if let Some(client) = guard.take() {
            let msg = serde_json::to_string(&ProtocolMessage::Unpair {}).unwrap();
            let _ = client.sender.send(msg).await;
            // 给消息发送一点时间
            tokio::time::sleep(std::time::Duration::from_millis(100)).await;
            client.abort_handle.abort();
            log::info!("已向 {} 发送 unpair 并断开", client.addr);
        }
    }

    #[allow(dead_code)]
    pub fn port(&self) -> u16 {
        self.port
    }
}

/// 处理新的 TCP 连接：在 HTTP Upgrade 阶段验证 token
async fn handle_new_connection(
    stream: TcpStream,
    addr: SocketAddr,
    pairing: Arc<PairingManager>,
    paired_client: Arc<Mutex<Option<PairedClient>>>,
    incoming_tx: mpsc::Sender<ClipMessage>,
    conn_event_tx: mpsc::Sender<WsConnectionEvent>,
) {
    crate::debug_log(&format!("WebSocket 新连接请求: {}", addr));

    // 使用 accept_hdr_async 在 HTTP Upgrade 阶段验证 token
    let pairing_for_cb = pairing.clone();
    let ws_stream = match tokio_tungstenite::accept_hdr_async(stream, move |req: &http::Request<()>, resp: http::Response<()>| {
        // 从 URI query 中提取 token
        let uri = req.uri();
        let query = uri.query().unwrap_or("");
        let token = query
            .split('&')
            .find_map(|pair| {
                let mut kv = pair.splitn(2, '=');
                let key = kv.next()?;
                let val = kv.next()?;
                if key == "token" { Some(val.to_string()) } else { None }
            });

        match token {
            Some(t) if pairing_for_cb.verify_token(&t) => {
                log::info!("WebSocket Token 验证通过: {}", addr);
                Ok(resp)
            }
            Some(_) => {
                log::warn!("WebSocket Token 验证失败（token 不匹配）: {}", addr);
                Err(http::Response::builder()
                    .status(http::StatusCode::UNAUTHORIZED)
                    .body(Some("Invalid token".to_string()))
                    .unwrap())
            }
            None => {
                log::warn!("WebSocket 连接未携带 token: {}", addr);
                Err(http::Response::builder()
                    .status(http::StatusCode::UNAUTHORIZED)
                    .body(Some("Token required".to_string()))
                    .unwrap())
            }
        }
    }).await {
        Ok(ws) => ws,
        Err(e) => {
            log::info!("WebSocket 连接被拒绝 {}: {}", addr, e);
            return;
        }
    };

    // Token 验证通过，处理 1:1 踢人逻辑
    {
        let mut guard = paired_client.lock().await;
        if let Some(old_client) = guard.take() {
            // 踢掉旧设备
            let kick_msg = serde_json::to_string(&ProtocolMessage::Kicked {
                reason: "new_device_paired".to_string(),
            }).unwrap();
            let _ = old_client.sender.send(kick_msg).await;
            // 给消息发送一点时间
            tokio::time::sleep(std::time::Duration::from_millis(100)).await;
            old_client.abort_handle.abort();
            crate::debug_log(&format!("旧设备 {} 已被踢下线", old_client.addr));

            // 通知旧设备断开
            let _ = conn_event_tx.send(WsConnectionEvent::Disconnected).await;
        }
    }

    // 创建新设备的发送通道
    let (client_tx, mut client_rx) = mpsc::channel::<String>(32);
    let (mut ws_sender, mut ws_receiver) = ws_stream.split();

    // 发送 pair_ok 消息
    let pair_ok_msg = serde_json::to_string(&ProtocolMessage::PairOk {
        device: pairing.device_name().to_string(),
    }).unwrap();
    if ws_sender.send(Message::Text(pair_ok_msg.into())).await.is_err() {
        log::error!("发送 pair_ok 失败: {}", addr);
        return;
    }

    // 更新配对状态
    pairing.set_paired(format!("android-{}", addr));

    // 通知连接事件
    let _ = conn_event_tx.send(WsConnectionEvent::Connected).await;

    // 启动连接处理任务
    let conn_event_tx_clone = conn_event_tx.clone();
    let pairing_clone = pairing.clone();
    let paired_client_clone = paired_client.clone();
    let task_handle = tokio::spawn(async move {
        let mut ping_interval = tokio::time::interval(std::time::Duration::from_secs(30));
        ping_interval.tick().await; // 跳过第一次

        loop {
            tokio::select! {
                // 接收来自客户端的消息
                msg = ws_receiver.next() => {
                    match msg {
                        Some(Ok(Message::Text(text))) => {
                            // 尝试解析为带 type 字段的消息
                            if let Ok(value) = serde_json::from_str::<serde_json::Value>(&text) {
                                let msg_type = value.get("type")
                                    .and_then(|t| t.as_str())
                                    .unwrap_or("clip");

                                match msg_type {
                                    "clip" => {
                                        // 兼容无 type 字段的消息（视为 clip）
                                        match serde_json::from_str::<ClipMessage>(&text) {
                                            Ok(mut clip_msg) => {
                                                clip_msg.msg_type = "clip".to_string();
                                                if let Err(e) = incoming_tx.send(clip_msg).await {
                                                    log::error!("转发 clip 消息失败: {}", e);
                                                    break;
                                                }
                                            }
                                            Err(e) => {
                                                log::warn!("clip 消息解析失败 {}: {}", addr, e);
                                            }
                                        }
                                    }
                                    "unpair" => {
                                        log::info!("收到 Android 端 unpair 消息: {}", addr);
                                        pairing_clone.set_waiting_pair();
                                        break;
                                    }
                                    other => {
                                        log::debug!("忽略未知消息类型 {}: {}", other, addr);
                                    }
                                }
                            } else {
                                // JSON 解析失败，尝试作为旧格式 clip 消息
                                log::warn!("消息 JSON 解析失败 {}: {}", addr, text);
                            }
                        }
                        Some(Ok(Message::Close(_))) => {
                            log::info!("WebSocket 客户端 {} 关闭连接", addr);
                            break;
                        }
                        Some(Ok(Message::Ping(_))) => {
                            log::debug!("WebSocket Ping from {}", addr);
                        }
                        Some(Err(e)) => {
                            log::error!("WebSocket 读取错误 {}: {}", addr, e);
                            break;
                        }
                        None => {
                            log::info!("WebSocket 流结束: {}", addr);
                            break;
                        }
                        _ => {}
                    }
                }
                // 从服务端发送消息到客户端
                msg = client_rx.recv() => {
                    match msg {
                        Some(msg) => {
                            if ws_sender.send(Message::Text(msg.into())).await.is_err() {
                                log::warn!("WebSocket 发送失败: {}", addr);
                                break;
                            }
                        }
                        None => break,
                    }
                }
                // 心跳 Ping
                _ = ping_interval.tick() => {
                    if ws_sender.send(Message::Ping(vec![].into())).await.is_err() {
                        log::info!("WebSocket Ping 失败: {}", addr);
                        break;
                    }
                }
            }
        }

        // 连接断开，清理
        {
            let mut guard = paired_client_clone.lock().await;
            if let Some(ref client) = *guard {
                if client.addr == addr {
                    guard.take();
                    pairing_clone.set_waiting_pair();
                    crate::debug_log(&format!("WebSocket 已配对客户端断开: {}", addr));
                }
            }
        }

        // 通知断开事件
        let _ = conn_event_tx_clone.send(WsConnectionEvent::Disconnected).await;
    });

    // 注册为已配对客户端
    {
        let mut guard = paired_client.lock().await;
        *guard = Some(PairedClient {
            addr,
            device_id: format!("android-{}", addr),
            sender: client_tx,
            abort_handle: task_handle,
        });
    }

    crate::debug_log(&format!("WebSocket 客户端已配对: {}", addr));
}
