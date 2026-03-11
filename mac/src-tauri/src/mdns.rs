use std::process::{Child, Command};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// mDNS/Bonjour 服务注册（通过 macOS 原生 dns-sd 命令）
///
/// mdns_sd crate 的纯 Rust mDNS daemon 与 macOS 系统 mDNSResponder 冲突，
/// 无法正确响应 PTR 查询。改用系统 dns-sd 命令注册服务，通过 mDNSResponder
/// 处理，确保 Android NSD 能正确发现。
///
/// 实例名格式: `gc-{mac_hash}`，便于 Android 端按 mac_hash 过滤匹配。
///
/// 内置监控线程：每 30 秒检查 dns-sd 进程存活，崩溃后自动重启。
pub struct MdnsService {
    child: Arc<Mutex<Child>>,
    instance_name: String,
    shutdown: Arc<AtomicBool>,
}

impl MdnsService {
    /// 注册 _ghostclip._tcp 服务
    ///
    /// - `port`: WebSocket 服务端口
    /// - `mac_hash`: 硬件 MAC 地址 sha256 前 12 位 hex
    /// - `device_id`: 设备 ID（保留用于 TXT 记录）
    pub fn register(port: u16, mac_hash: &str, device_id: &str) -> Result<Self, String> {
        let instance_name = format!("gc-{}", mac_hash);
        let child = Self::spawn_dns_sd(&instance_name, port, mac_hash, device_id)?;

        let child = Arc::new(Mutex::new(child));
        let shutdown = Arc::new(AtomicBool::new(false));

        // 监控线程：定期检查 dns-sd 进程存活，崩溃后自动重启
        let child_monitor = child.clone();
        let shutdown_monitor = shutdown.clone();
        let name_monitor = instance_name.clone();
        let mac_hash_monitor = mac_hash.to_string();
        let did_monitor = device_id.to_string();
        std::thread::spawn(move || {
            while !shutdown_monitor.load(Ordering::Relaxed) {
                std::thread::sleep(Duration::from_secs(30));
                if shutdown_monitor.load(Ordering::Relaxed) {
                    break;
                }
                let mut guard = child_monitor.lock().unwrap();
                match guard.try_wait() {
                    Ok(Some(status)) => {
                        log::warn!("dns-sd 进程已退出 (status: {}), 重新启动...", status);
                        match Self::spawn_dns_sd(&name_monitor, port, &mac_hash_monitor, &did_monitor) {
                            Ok(new_child) => {
                                *guard = new_child;
                                crate::debug_log("dns-sd 进程已自动重启，mDNS 服务恢复");
                            }
                            Err(e) => log::error!("dns-sd 重启失败: {}", e),
                        }
                    }
                    Ok(None) => {} // 进程仍在运行
                    Err(e) => log::error!("检查 dns-sd 状态失败: {}", e),
                }
            }
        });

        log::info!(
            "mDNS 服务已注册（via dns-sd）: {} (端口 {})",
            instance_name,
            port
        );

        Ok(Self {
            child,
            instance_name,
            shutdown,
        })
    }

    fn spawn_dns_sd(instance_name: &str, port: u16, mac_hash: &str, device_id: &str) -> Result<Child, String> {
        // dns-sd -R <name> <type> <domain> <port> [<txt>...]
        Command::new("dns-sd")
            .args([
                "-R",
                instance_name,
                "_ghostclip._tcp",
                "local",
                &port.to_string(),
                &format!("device_id={}", device_id),
                &format!("mac_hash={}", mac_hash),
                "version=0.1.0",
            ])
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .map_err(|e| format!("启动 dns-sd 注册失败: {}", e))
    }
}

impl Drop for MdnsService {
    fn drop(&mut self) {
        self.shutdown.store(true, Ordering::Relaxed);
        let mut guard = self.child.lock().unwrap();
        let _ = guard.kill();
        let _ = guard.wait();
        log::info!("mDNS 服务已注销: {}", self.instance_name);
    }
}
