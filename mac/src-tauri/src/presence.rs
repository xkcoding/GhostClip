use crate::cloud_client::{CloudClient, CloudError};
use std::sync::Arc;
use tokio::sync::watch;
use tokio::time::{Duration, Instant};

/// 设备在线感知状态
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PresenceMode {
    /// 无对端在线：每 30s GET /peers
    Idle,
    /// 有对端在线：每 3s GET /clip + 每 10min PUT /register
    Poll,
}

impl std::fmt::Display for PresenceMode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PresenceMode::Idle => write!(f, "IDLE"),
            PresenceMode::Poll => write!(f, "POLL"),
        }
    }
}

const IDLE_PEER_CHECK_INTERVAL: Duration = Duration::from_secs(10);
const POLL_CLIP_INTERVAL: Duration = Duration::from_secs(2);
const POLL_HEARTBEAT_INTERVAL: Duration = Duration::from_secs(600); // 10 分钟
const POLL_PEER_CHECK_INTERVAL: Duration = Duration::from_secs(30); // 30 秒

/// 设备在线感知状态机
pub struct PresenceStateMachine {
    mode_tx: watch::Sender<PresenceMode>,
    mode_rx: watch::Receiver<PresenceMode>,
}

impl PresenceStateMachine {
    pub fn new() -> Self {
        let (mode_tx, mode_rx) = watch::channel(PresenceMode::Idle);
        Self { mode_tx, mode_rx }
    }

    pub fn mode(&self) -> PresenceMode {
        *self.mode_rx.borrow()
    }

    #[allow(dead_code)]
    pub fn subscribe(&self) -> watch::Receiver<PresenceMode> {
        self.mode_rx.clone()
    }

    /// 启动状态机主循环，返回任务 Handle
    pub fn start(
        &self,
        cloud_client: Arc<CloudClient>,
        clip_callback: tokio::sync::mpsc::Sender<crate::cloud_client::ClipRecord>,
        error_tx: tokio::sync::mpsc::Sender<CloudError>,
    ) -> tokio::task::JoinHandle<()> {
        let mode_tx = self.mode_tx.clone();
        let mode_rx = self.mode_rx.clone();

        tokio::spawn(async move {
            let mut last_hash: Option<String> = None;
            let mut last_heartbeat = Instant::now();
            let mut last_peer_check = Instant::now();
            let mut consecutive_errors: u32 = 0;
            let mut auth_error_reported = false;

            // 报告云端错误（auth 错误只报告一次，避免刷屏）
            let report_error = |error: &CloudError,
                                error_tx: &tokio::sync::mpsc::Sender<CloudError>,
                                auth_reported: &mut bool,
                                consec: &mut u32| {
                match error {
                    CloudError::AuthFailed(_) => {
                        if !*auth_reported {
                            *auth_reported = true;
                            let _ = error_tx.try_send(error.clone());
                        }
                    }
                    _ => {
                        *consec += 1;
                        // 连续失败 3 次才报告，避免瞬时网络波动误报
                        if *consec == 3 {
                            let _ = error_tx.try_send(error.clone());
                        }
                    }
                }
            };

            // 初始注册
            if let Err(e) = cloud_client.register().await {
                log::error!("初始设备注册失败: {}", e);
                report_error(
                    &e,
                    &error_tx,
                    &mut auth_error_reported,
                    &mut consecutive_errors,
                );
            }

            loop {
                let current_mode = *mode_rx.borrow();

                match current_mode {
                    PresenceMode::Idle => {
                        // 每 30s 检查是否有对端上线
                        tokio::time::sleep(IDLE_PEER_CHECK_INTERVAL).await;

                        match cloud_client.get_peers().await {
                            Ok(peers) => {
                                consecutive_errors = 0;
                                let has_android = peers.iter().any(|p| {
                                    p.device_type == "android"
                                        && p.device_id != cloud_client.device_id()
                                });
                                if has_android {
                                    log::info!("检测到 Android 对端在线，切换到 POLL 模式");
                                    let _ = mode_tx.send(PresenceMode::Poll);

                                    // 切换到 POLL 模式后立即注册并发送心跳
                                    if let Err(e) = cloud_client.register().await {
                                        log::error!("设备注册失败: {}", e);
                                        report_error(
                                            &e,
                                            &error_tx,
                                            &mut auth_error_reported,
                                            &mut consecutive_errors,
                                        );
                                    }
                                    last_heartbeat = Instant::now();
                                }
                            }
                            Err(e) => {
                                log::warn!("检查 peers 失败: {}", e);
                                report_error(
                                    &e,
                                    &error_tx,
                                    &mut auth_error_reported,
                                    &mut consecutive_errors,
                                );
                            }
                        }
                    }
                    PresenceMode::Poll => {
                        // 每 3s 轮询 /clip
                        tokio::time::sleep(POLL_CLIP_INTERVAL).await;

                        match cloud_client.get_clip(last_hash.as_deref()).await {
                            Ok(Some(record)) => {
                                consecutive_errors = 0;
                                // 排除自己发送的
                                if record.device_id != cloud_client.device_id() {
                                    log::info!(
                                        "收到云端剪贴板 - 来自: {}, 哈希: {}",
                                        record.device_id,
                                        record.hash
                                    );
                                    last_hash = Some(record.hash.clone());
                                    if let Err(e) = clip_callback.send(record).await {
                                        log::error!("转发云端剪贴板失败: {}", e);
                                    }
                                } else {
                                    last_hash = Some(record.hash.clone());
                                }
                            }
                            Ok(None) => {
                                consecutive_errors = 0;
                            }
                            Err(e) => {
                                log::warn!("云端轮询失败: {}", e);
                                report_error(
                                    &e,
                                    &error_tx,
                                    &mut auth_error_reported,
                                    &mut consecutive_errors,
                                );
                            }
                        }

                        // 每 10 分钟心跳
                        if last_heartbeat.elapsed() >= POLL_HEARTBEAT_INTERVAL {
                            if let Err(e) = cloud_client.register().await {
                                log::error!("心跳注册失败: {}", e);
                                report_error(
                                    &e,
                                    &error_tx,
                                    &mut auth_error_reported,
                                    &mut consecutive_errors,
                                );
                            }
                            last_heartbeat = Instant::now();
                        }

                        // 定期检查对端是否还在线（独立计时器，不受心跳影响）
                        if last_peer_check.elapsed() >= POLL_PEER_CHECK_INTERVAL {
                            last_peer_check = Instant::now();
                            match cloud_client.get_peers().await {
                                Ok(peers) => {
                                    let has_android = peers.iter().any(|p| {
                                        p.device_type == "android"
                                            && p.device_id != cloud_client.device_id()
                                    });
                                    if !has_android {
                                        log::info!("Android 对端已离线，切换到 IDLE 模式");
                                        let _ = mode_tx.send(PresenceMode::Idle);
                                    }
                                }
                                Err(e) => {
                                    log::warn!("检查 peers 失败: {}", e);
                                    report_error(
                                        &e,
                                        &error_tx,
                                        &mut auth_error_reported,
                                        &mut consecutive_errors,
                                    );
                                }
                            }
                        }
                    }
                }
            }
        })
    }
}
