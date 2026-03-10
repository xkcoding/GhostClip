use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc;

/// 网络变更事件
#[derive(Debug, Clone)]
pub struct NetworkChangeEvent {
    /// 新的 WiFi SSID（可能为空表示断开）
    pub ssid: Option<String>,
}

/// 网络变更监控器
///
/// 定期检查当前 WiFi SSID，变化时发送事件。
pub struct NetMonitor {
    shutdown: Arc<AtomicBool>,
}

impl NetMonitor {
    /// 启动网络变更监控，返回 (监控器, 事件接收通道)
    pub fn start() -> (Self, mpsc::Receiver<NetworkChangeEvent>) {
        let (tx, rx) = mpsc::channel(8);
        let shutdown = Arc::new(AtomicBool::new(false));
        let shutdown_clone = shutdown.clone();

        std::thread::spawn(move || {
            let mut last_ssid = get_current_ssid();
            log::info!("网络监控已启动，当前 WiFi: {:?}", last_ssid);

            while !shutdown_clone.load(Ordering::Relaxed) {
                std::thread::sleep(Duration::from_secs(5));
                if shutdown_clone.load(Ordering::Relaxed) {
                    break;
                }

                let current_ssid = get_current_ssid();
                if current_ssid != last_ssid {
                    log::info!(
                        "WiFi 网络变更: {:?} → {:?}",
                        last_ssid, current_ssid
                    );
                    last_ssid = current_ssid.clone();
                    let _ = tx.blocking_send(NetworkChangeEvent {
                        ssid: current_ssid,
                    });
                }
            }
            log::info!("网络监控已停止");
        });

        (Self { shutdown }, rx)
    }

    /// 停止监控
    pub fn stop(&self) {
        self.shutdown.store(true, Ordering::Relaxed);
    }
}

impl Drop for NetMonitor {
    fn drop(&mut self) {
        self.stop();
    }
}

/// 获取当前 WiFi SSID
fn get_current_ssid() -> Option<String> {
    // macOS: 使用 networksetup 获取当前 WiFi 网络名
    let output = Command::new("networksetup")
        .args(["-getairportnetwork", "en0"])
        .output()
        .ok()?;

    let stdout = String::from_utf8_lossy(&output.stdout);
    // 输出格式: "Current Wi-Fi Network: <SSID>" 或 "You are not associated with an AirPort network."
    if let Some(ssid) = stdout.strip_prefix("Current Wi-Fi Network: ") {
        let ssid = ssid.trim().to_string();
        if ssid.is_empty() {
            None
        } else {
            Some(ssid)
        }
    } else {
        None
    }
}
