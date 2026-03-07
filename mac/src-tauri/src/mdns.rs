use std::process::{Child, Command};

/// mDNS/Bonjour 服务注册（通过 macOS 原生 dns-sd 命令）
///
/// mdns_sd crate 的纯 Rust mDNS daemon 与 macOS 系统 mDNSResponder 冲突，
/// 无法正确响应 PTR 查询。改用系统 dns-sd 命令注册服务，通过 mDNSResponder
/// 处理，确保 Android NSD 能正确发现。
pub struct MdnsService {
    child: Child,
    instance_name: String,
}

impl MdnsService {
    /// 注册 _ghostclip._tcp 服务
    pub fn register(port: u16, device_id: &str) -> Result<Self, String> {
        let instance_name = format!("GhostClip-{}", &device_id[..8.min(device_id.len())]);

        // dns-sd -R <name> <type> <domain> <port> [<txt>...]
        let child = Command::new("dns-sd")
            .args([
                "-R",
                &instance_name,
                "_ghostclip._tcp",
                "local",
                &port.to_string(),
                &format!("device_id={}", device_id),
                "version=0.1.0",
            ])
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .map_err(|e| format!("启动 dns-sd 注册失败: {}", e))?;

        log::info!(
            "mDNS 服务已注册（via dns-sd）: {} (端口 {})",
            instance_name,
            port
        );

        Ok(Self {
            child,
            instance_name,
        })
    }
}

impl Drop for MdnsService {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
        log::info!("mDNS 服务已注销: {}", self.instance_name);
    }
}
