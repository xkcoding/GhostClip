use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;

const SERVICE_TYPE: &str = "_ghostclip._tcp.local.";

/// mDNS/Bonjour 服务注册
pub struct MdnsService {
    daemon: ServiceDaemon,
    service_fullname: String,
}

impl MdnsService {
    /// 注册 _ghostclip._tcp 服务
    pub fn register(port: u16, device_id: &str) -> Result<Self, String> {
        let daemon =
            ServiceDaemon::new().map_err(|e| format!("创建 mDNS daemon 失败: {}", e))?;

        let hostname = hostname::get()
            .map(|h| h.to_string_lossy().to_string())
            .unwrap_or_else(|_| "ghostclip-mac".to_string());

        let instance_name = format!("GhostClip-{}", &device_id[..8.min(device_id.len())]);

        let mut properties = HashMap::new();
        properties.insert("device_id".to_string(), device_id.to_string());
        properties.insert("version".to_string(), "0.1.0".to_string());

        let service_info = ServiceInfo::new(
            SERVICE_TYPE,
            &instance_name,
            &format!("{}.local.", hostname),
            (),
            port,
            properties,
        )
        .map_err(|e| format!("创建 mDNS ServiceInfo 失败: {}", e))?;

        let fullname = service_info.get_fullname().to_string();

        daemon
            .register(service_info)
            .map_err(|e| format!("注册 mDNS 服务失败: {}", e))?;

        log::info!(
            "mDNS 服务已注册: {} (端口 {})",
            instance_name,
            port
        );

        Ok(Self {
            daemon,
            service_fullname: fullname,
        })
    }

    /// 注销 mDNS 服务
    pub fn unregister(&self) -> Result<(), String> {
        self.daemon
            .unregister(&self.service_fullname)
            .map_err(|e| format!("注销 mDNS 服务失败: {}", e))?;
        log::info!("mDNS 服务已注销");
        Ok(())
    }
}

impl Drop for MdnsService {
    fn drop(&mut self) {
        let _ = self.unregister();
    }
}
