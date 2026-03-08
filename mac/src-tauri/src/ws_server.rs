use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, RwLock};
use tokio_tungstenite::tungstenite::Message;

/// WebSocket 消息协议
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipMessage {
    pub device_id: String,
    pub text: String,
    pub hash: String,
    pub timestamp: u64,
}

/// 已连接的客户端信息
#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct ConnectedClient {
    pub device_id: String,
    pub addr: SocketAddr,
}

/// WebSocket 连接事件
#[derive(Debug, Clone)]
pub enum WsConnectionEvent {
    /// 客户端已连接
    Connected,
    /// 客户端已断开
    Disconnected,
}

/// WebSocket 服务器
pub struct WsServer {
    /// 绑定的端口
    port: u16,
    /// 广播频道：向所有连接的客户端发送消息
    sender: broadcast::Sender<String>,
    /// 当前已连接的客户端
    clients: Arc<RwLock<HashMap<SocketAddr, ConnectedClient>>>,
    /// 接收来自客户端的消息回调
    incoming_tx: tokio::sync::mpsc::Sender<ClipMessage>,
    /// 连接事件通知
    conn_event_tx: tokio::sync::mpsc::Sender<WsConnectionEvent>,
}

impl WsServer {
    pub fn new(
        port: u16,
        incoming_tx: tokio::sync::mpsc::Sender<ClipMessage>,
        conn_event_tx: tokio::sync::mpsc::Sender<WsConnectionEvent>,
    ) -> Self {
        let (sender, _) = broadcast::channel(32);
        Self {
            port,
            sender,
            clients: Arc::new(RwLock::new(HashMap::new())),
            incoming_tx,
            conn_event_tx,
        }
    }

    /// 启动 WebSocket 服务器，返回实际绑定的端口
    pub async fn start(&self) -> Result<u16, String> {
        let addr = format!("0.0.0.0:{}", self.port);
        let listener = TcpListener::bind(&addr)
            .await
            .map_err(|e| format!("WebSocket 绑定 {} 失败: {}", addr, e))?;

        let local_port = listener
            .local_addr()
            .map_err(|e| format!("获取本地地址失败: {}", e))?
            .port();

        crate::debug_log(&format!("WebSocket Server 已启动: 0.0.0.0:{}", local_port));

        let sender = self.sender.clone();
        let clients = self.clients.clone();
        let incoming_tx = self.incoming_tx.clone();
        let conn_event_tx = self.conn_event_tx.clone();

        tokio::spawn(async move {
            loop {
                match listener.accept().await {
                    Ok((stream, addr)) => {
                        crate::debug_log(&format!("WebSocket 新连接: {}", addr));
                        let sender = sender.clone();
                        let clients = clients.clone();
                        let incoming_tx = incoming_tx.clone();
                        let conn_event_tx = conn_event_tx.clone();
                        tokio::spawn(handle_connection(
                            stream,
                            addr,
                            sender,
                            clients,
                            incoming_tx,
                            conn_event_tx,
                        ));
                    }
                    Err(e) => {
                        log::error!("WebSocket 接受连接失败: {}", e);
                    }
                }
            }
        });

        Ok(local_port)
    }

    /// 向所有连接的客户端广播消息
    pub fn broadcast(&self, msg: &ClipMessage) -> Result<(), String> {
        let json = serde_json::to_string(msg).map_err(|e| format!("序列化失败: {}", e))?;
        // broadcast send 返回接收者数量，0 也不算错误
        let _ = self.sender.send(json);
        Ok(())
    }

    /// 获取当前已连接的客户端数量
    pub async fn client_count(&self) -> usize {
        self.clients.read().await.len()
    }

    /// 获取已连接的客户端列表
    #[allow(dead_code)]
    pub async fn connected_clients(&self) -> Vec<ConnectedClient> {
        self.clients.read().await.values().cloned().collect()
    }

    #[allow(dead_code)]
    pub fn port(&self) -> u16 {
        self.port
    }
}

async fn handle_connection(
    stream: TcpStream,
    addr: SocketAddr,
    sender: broadcast::Sender<String>,
    clients: Arc<RwLock<HashMap<SocketAddr, ConnectedClient>>>,
    incoming_tx: tokio::sync::mpsc::Sender<ClipMessage>,
    conn_event_tx: tokio::sync::mpsc::Sender<WsConnectionEvent>,
) {
    let ws_stream = match tokio_tungstenite::accept_async(stream).await {
        Ok(ws) => ws,
        Err(e) => {
            log::error!("WebSocket 握手失败 {}: {}", addr, e);
            return;
        }
    };

    let (mut ws_sender, mut ws_receiver) = ws_stream.split();
    let mut rx = sender.subscribe();

    // 注册客户端（device_id 初始为未知，等第一条消息确认）
    {
        let mut clients_lock = clients.write().await;
        clients_lock.insert(
            addr,
            ConnectedClient {
                device_id: String::new(),
                addr,
            },
        );
    }

    // 通知连接事件
    let _ = conn_event_tx.send(WsConnectionEvent::Connected).await;

    let clients_for_read = clients.clone();
    let read_task = tokio::spawn(async move {
        while let Some(msg_result) = ws_receiver.next().await {
            match msg_result {
                Ok(Message::Text(text)) => {
                    match serde_json::from_str::<ClipMessage>(&text) {
                        Ok(clip_msg) => {
                            // 更新客户端 device_id
                            {
                                let mut clients_lock = clients_for_read.write().await;
                                if let Some(client) = clients_lock.get_mut(&addr) {
                                    if client.device_id.is_empty() {
                                        client.device_id = clip_msg.device_id.clone();
                                        crate::debug_log(&format!(
                                            "WebSocket 客户端 {} 确认设备: {}",
                                            addr,
                                            clip_msg.device_id
                                        ));
                                    }
                                }
                            }
                            if let Err(e) = incoming_tx.send(clip_msg).await {
                                log::error!("转发 WebSocket 消息失败: {}", e);
                                break;
                            }
                        }
                        Err(e) => {
                            log::warn!("WebSocket 消息解析失败 {}: {}", addr, e);
                        }
                    }
                }
                Ok(Message::Close(_)) => {
                    log::info!("WebSocket 客户端 {} 关闭连接", addr);
                    break;
                }
                Ok(Message::Ping(_)) => {
                    log::debug!("WebSocket Ping from {}", addr);
                }
                Err(e) => {
                    log::error!("WebSocket 读取错误 {}: {}", addr, e);
                    break;
                }
                _ => {}
            }
        }
    });

    let write_task = tokio::spawn(async move {
        while let Ok(msg) = rx.recv().await {
            if ws_sender.send(Message::Text(msg.into())).await.is_err() {
                break;
            }
        }
    });

    // 等待任一任务结束
    tokio::select! {
        _ = read_task => {},
        _ = write_task => {},
    }

    // 清除客户端
    {
        let mut clients_lock = clients.write().await;
        if let Some(client) = clients_lock.remove(&addr) {
            crate::debug_log(&format!("WebSocket 客户端断开: {} ({})", addr, client.device_id));
        }
    }

    // 通知断开事件
    let _ = conn_event_tx.send(WsConnectionEvent::Disconnected).await;
}
