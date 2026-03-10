use crate::cloud_client::{CloudClient, CloudError, ClipRecord};
use crate::hash_pool::{md5_hash, HashPool};
use crate::mdns::MdnsService;
use crate::pairing::PairingManager;
use crate::presence::{PresenceMode, PresenceStateMachine};
use crate::ws_server::{ClipMessage, WsConnectionEvent, WsServer};
use std::sync::Arc;
use tokio::sync::{mpsc, watch};

/// 网络连接状态
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionState {
    /// 未连接
    Disconnected,
    /// 局域网直连
    Lan,
    /// 云端中转
    Cloud,
}

impl std::fmt::Display for ConnectionState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ConnectionState::Disconnected => write!(f, "未连接"),
            ConnectionState::Lan => write!(f, "局域网"),
            ConnectionState::Cloud => write!(f, "云端"),
        }
    }
}

/// 网络管理器：协调 LAN (WebSocket) 和 Cloud (HTTP) 通道
#[allow(dead_code)]
pub struct NetworkManager {
    ws_server: Arc<WsServer>,
    cloud_client: Option<Arc<CloudClient>>,
    presence: Arc<PresenceStateMachine>,
    hash_pool: Arc<HashPool>,
    pairing: Arc<PairingManager>,
    _mdns: Option<MdnsService>,
    has_cloud: bool,
    state_tx: watch::Sender<ConnectionState>,
    state_rx: watch::Receiver<ConnectionState>,
    /// 所有后台任务 handle，shutdown 时 abort
    task_handles: std::sync::Mutex<Vec<tokio::task::JoinHandle<()>>>,
}

/// 网络管理器配置
pub struct NetworkConfig {
    pub ws_port: u16,
    pub cloud_url: Option<String>,
    pub cloud_token: Option<String>,
    pub device_id: String,
    pub pairing: Arc<PairingManager>,
}

impl NetworkManager {
    /// 初始化并启动所有网络服务
    pub async fn start(
        config: NetworkConfig,
        hash_pool: Arc<HashPool>,
        on_receive: mpsc::Sender<String>,
        error_tx: mpsc::Sender<CloudError>,
    ) -> Result<Self, String> {
        // 创建 WebSocket 消息通道和连接事件通道
        let (ws_incoming_tx, mut ws_incoming_rx) = mpsc::channel::<ClipMessage>(32);
        let (conn_event_tx, mut conn_event_rx) = mpsc::channel::<WsConnectionEvent>(32);

        // 启动 WebSocket Server（带 token 鉴权）
        let ws_server = Arc::new(WsServer::new(config.ws_port, config.pairing.clone(), ws_incoming_tx, conn_event_tx));
        let (actual_port, ws_handle) = ws_server.start().await?;
        let mut task_handles: Vec<tokio::task::JoinHandle<()>> = vec![ws_handle];

        // 注册 mDNS 服务（使用 mac_hash 作为实例标识）
        let pairing = config.pairing.clone();
        let mdns = match MdnsService::register(actual_port, pairing.mac_hash(), &config.device_id) {
            Ok(m) => {
                log::info!("mDNS 服务注册成功");
                Some(m)
            }
            Err(e) => {
                log::warn!("mDNS 服务注册失败（将仅使用云端）: {}", e);
                None
            }
        };

        // 启动云端客户端和状态机（含 URL 格式校验）
        let cloud_client = match (&config.cloud_url, &config.cloud_token) {
            (Some(url), Some(token)) if !url.is_empty() && !token.is_empty() => {
                // 校验 URL 格式
                if !url.starts_with("http://") && !url.starts_with("https://") {
                    log::error!("云端 URL 格式无效（需以 http:// 或 https:// 开头）: {}", url);
                    let _ = error_tx
                        .send(CloudError::Other(format!(
                            "云端 URL 格式无效: {}（需以 http:// 或 https:// 开头）",
                            url
                        )))
                        .await;
                    None
                } else {
                    Some(Arc::new(CloudClient::new(
                        url.clone(),
                        token.clone(),
                        config.device_id.clone(),
                    )))
                }
            }
            _ => {
                log::info!("未配置云端参数，仅使用局域网模式");
                None
            }
        };

        let presence = Arc::new(PresenceStateMachine::new());

        // 启动云端轮询状态机
        if let Some(ref client) = cloud_client {
            let (cloud_clip_tx, mut cloud_clip_rx) = mpsc::channel::<ClipRecord>(32);
            let presence_handle = presence.start(client.clone(), cloud_clip_tx, error_tx);
            task_handles.push(presence_handle);

            // 处理从云端收到的剪贴板内容
            let hash_pool_cloud = hash_pool.clone();
            let on_receive_cloud = on_receive.clone();
            task_handles.push(tokio::spawn(async move {
                while let Some(record) = cloud_clip_rx.recv().await {
                    // 去重检查
                    if hash_pool_cloud.check_and_insert(&record.text).is_some() {
                        log::info!("云端接收 - 写入剪贴板, 哈希: {}", record.hash);
                        let _ = on_receive_cloud.send(record.text).await;
                    } else {
                        log::debug!("云端接收 - 内容重复，跳过");
                    }
                }
            }));
        }

        // 处理从 WebSocket 收到的剪贴板内容
        let hash_pool_ws = hash_pool.clone();
        let on_receive_ws = on_receive.clone();
        task_handles.push(tokio::spawn(async move {
            while let Some(clip_msg) = ws_incoming_rx.recv().await {
                // 去重检查
                if hash_pool_ws.check_and_insert(&clip_msg.text).is_some() {
                    log::info!("局域网接收 - 写入剪贴板, 哈希: {}", clip_msg.hash);
                    let _ = on_receive_ws.send(clip_msg.text).await;
                } else {
                    log::debug!("局域网接收 - 内容重复，跳过");
                }
            }
        }));

        // 初始状态：有云端则 Cloud，否则 Disconnected（LAN 需要等 WS 客户端连接）
        let initial_state = if cloud_client.is_some() {
            ConnectionState::Cloud
        } else {
            ConnectionState::Disconnected
        };
        let (state_tx, state_rx) = watch::channel(initial_state);
        let has_cloud = cloud_client.is_some();

        // 监听 WS 连接事件，动态更新连接状态
        let state_tx_for_events = state_tx.clone();
        let ws_server_for_events = ws_server.clone();
        let has_cloud_for_events = has_cloud;
        task_handles.push(tokio::spawn(async move {
            while let Some(event) = conn_event_rx.recv().await {
                let client_count = ws_server_for_events.client_count().await;
                let new_state = match event {
                    WsConnectionEvent::Connected => {
                        log::info!("WS 客户端已连接，当前连接数: {}", client_count);
                        ConnectionState::Lan
                    }
                    WsConnectionEvent::Disconnected => {
                        log::info!("WS 客户端已断开，剩余连接数: {}", client_count);
                        if client_count > 0 {
                            ConnectionState::Lan
                        } else if has_cloud_for_events {
                            ConnectionState::Cloud
                        } else {
                            ConnectionState::Disconnected
                        }
                    }
                };
                let _ = state_tx_for_events.send(new_state);
            }
        }));

        Ok(Self {
            ws_server,
            cloud_client,
            presence,
            hash_pool,
            pairing,
            _mdns: mdns,
            has_cloud,
            state_tx,
            state_rx,
            task_handles: std::sync::Mutex::new(task_handles),
        })
    }

    /// 发送剪贴板内容到对端
    /// 策略：LAN 优先 -> 云端回退
    pub async fn send_clip(&self, text: &str, device_id: &str) -> Result<(), String> {
        let hash = md5_hash(text);
        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as u64;

        // 尝试局域网发送
        if self.ws_server.client_count().await > 0 {
            let msg = ClipMessage {
                msg_type: "clip".to_string(),
                device_id: device_id.to_string(),
                text: text.to_string(),
                hash: hash.clone(),
                timestamp,
            };
            if let Err(e) = self.ws_server.broadcast(&msg) {
                log::warn!("局域网发送失败: {}，尝试云端", e);
            } else {
                log::info!("局域网发送成功, 哈希: {}", hash);
                return Ok(());
            }
        }

        // 云端回退
        if let Some(ref client) = self.cloud_client {
            // 发送时顺便注册，确保对端 IDLE 检查能发现自己
            let _ = client.register().await;
            match client.post_clip(text, &hash).await {
                Ok(resp) => {
                    log::info!("云端发送成功, 状态: {}, 哈希: {}", resp.status, resp.hash);
                    Ok(())
                }
                Err(e) => {
                    let msg = e.to_string();
                    Err(msg)
                }
            }
        } else {
            Err("无可用网络通道（无局域网连接且未配置云端）".to_string())
        }
    }

    /// 获取当前连接状态
    pub fn connection_state(&self) -> ConnectionState {
        *self.state_rx.borrow()
    }

    /// 订阅连接状态变更
    pub fn subscribe_connection_state(&self) -> watch::Receiver<ConnectionState> {
        self.state_rx.clone()
    }

    /// 强制设置连接状态（用于错误恢复等场景）
    pub fn set_connection_state(&self, state: ConnectionState) {
        let _ = self.state_tx.send(state);
    }

    /// 获取配对管理器
    pub fn pairing(&self) -> &Arc<PairingManager> {
        &self.pairing
    }

    /// 向已配对设备发送 unpair 消息并断开连接
    pub async fn send_unpair_and_disconnect(&self) {
        self.ws_server.send_unpair_and_disconnect().await;
    }

    /// 获取当前 presence 模式
    pub fn presence_mode(&self) -> PresenceMode {
        self.presence.mode()
    }

    /// 关闭所有后台任务，释放端口等资源
    pub fn shutdown(&self) {
        let mut handles = self.task_handles.lock().unwrap();
        log::info!("正在关闭网络服务（abort {} 个任务）...", handles.len());
        for handle in handles.drain(..) {
            handle.abort();
        }
    }

    /// 获取 WebSocket 客户端数量
    pub async fn ws_client_count(&self) -> usize {
        self.ws_server.client_count().await
    }
}
