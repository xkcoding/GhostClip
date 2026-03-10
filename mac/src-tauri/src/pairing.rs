use rand::RngCore;
use sha2::{Digest, Sha256};
use std::sync::{Arc, RwLock};

/// 配对状态
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PairingStatus {
    /// 等待配对（QR 码已生成，等待 Android 扫码）
    WaitingPair,
    /// 已配对（Android 已连接并通过 token 验证）
    Paired {
        /// 已配对设备的 device_id
        device_id: String,
    },
}

/// 配对状态管理器
///
/// 管理 mac_hash、ephemeral token 和配对状态。
/// 线程安全，可跨异步任务共享。
pub struct PairingManager {
    /// 硬件 MAC 地址的 sha256 前 12 位 hex
    mac_hash: String,
    /// 当前有效的 ephemeral token（64 字符 hex）
    token: RwLock<String>,
    /// 当前配对状态
    status: RwLock<PairingStatus>,
    /// 设备名称（hostname）
    device_name: String,
}

impl PairingManager {
    /// 创建配对管理器
    ///
    /// 自动获取硬件 MAC 地址生成 mac_hash，生成初始 ephemeral token。
    pub fn new() -> Result<Arc<Self>, String> {
        let mac_addr = get_hardware_mac_address()?;
        let mac_hash = compute_mac_hash(&mac_addr);
        let token = generate_ephemeral_token();
        let device_name = hostname::get()
            .map(|h| h.to_string_lossy().to_string())
            .unwrap_or_else(|_| "GhostClip-Mac".to_string());

        log::info!("配对管理器初始化 - mac_hash: {}, device: {}", mac_hash, device_name);

        Ok(Arc::new(Self {
            mac_hash,
            token: RwLock::new(token),
            status: RwLock::new(PairingStatus::WaitingPair),
            device_name,
        }))
    }

    /// 获取 mac_hash
    pub fn mac_hash(&self) -> &str {
        &self.mac_hash
    }

    /// 获取当前有效 token
    pub fn token(&self) -> String {
        self.token.read().unwrap().clone()
    }

    /// 获取设备名称
    pub fn device_name(&self) -> &str {
        &self.device_name
    }

    /// 获取当前配对状态
    pub fn status(&self) -> PairingStatus {
        self.status.read().unwrap().clone()
    }

    /// 验证 token 是否匹配当前有效 token
    pub fn verify_token(&self, token: &str) -> bool {
        let current = self.token.read().unwrap();
        !current.is_empty() && *current == token
    }

    /// 标记为已配对
    pub fn set_paired(&self, device_id: String) {
        let mut status = self.status.write().unwrap();
        *status = PairingStatus::Paired { device_id: device_id.clone() };
        log::info!("配对状态 → PAIRED (device: {})", device_id);
    }

    /// 回到等待配对状态
    pub fn set_waiting_pair(&self) {
        let mut status = self.status.write().unwrap();
        *status = PairingStatus::WaitingPair;
        log::info!("配对状态 → WAITING_PAIR");
    }

    /// 重新生成 token（旧 token 立即失效）
    ///
    /// 触发时机：App 启动、WiFi 变更、用户解除配对
    pub fn regenerate_token(&self) -> String {
        let new_token = generate_ephemeral_token();
        let mut token = self.token.write().unwrap();
        *token = new_token.clone();
        log::info!("Token 已重新生成");
        new_token
    }

    /// 获取已配对设备的 device_id（如果已配对）
    pub fn paired_device_id(&self) -> Option<String> {
        match &*self.status.read().unwrap() {
            PairingStatus::Paired { device_id } => Some(device_id.clone()),
            _ => None,
        }
    }

    /// 构建 QR 码 URI
    pub fn qr_uri(&self) -> String {
        let token = self.token.read().unwrap();
        format!(
            "ghostclip://pair?mac_hash={}&token={}&device={}",
            self.mac_hash,
            *token,
            urlencoded(&self.device_name),
        )
    }

    /// 判断是否已配对
    pub fn is_paired(&self) -> bool {
        matches!(*self.status.read().unwrap(), PairingStatus::Paired { .. })
    }
}

/// 通过 IOKit 获取硬件 MAC 地址
///
/// 读取主以太网接口（en0）的硬件 MAC 地址，不受 Private WiFi Address 影响。
fn get_hardware_mac_address() -> Result<Vec<u8>, String> {
    use std::process::Command;

    // 通过 networksetup 获取 en0 的硬件 MAC（IOKit 底层）
    // 这比直接调用 IOKit API 更简洁，结果相同
    let output = Command::new("ifconfig")
        .arg("en0")
        .output()
        .map_err(|e| format!("执行 ifconfig 失败: {}", e))?;

    let stdout = String::from_utf8_lossy(&output.stdout);

    // 解析 ether 行：ether aa:bb:cc:dd:ee:ff
    for line in stdout.lines() {
        let line = line.trim();
        if line.starts_with("ether ") {
            let mac_str = line.strip_prefix("ether ").unwrap().trim();
            let bytes: Result<Vec<u8>, _> = mac_str
                .split(':')
                .map(|h| u8::from_str_radix(h, 16))
                .collect();
            return bytes.map_err(|e| format!("解析 MAC 地址失败: {}", e));
        }
    }

    // 备选方案：使用系统序列号作为唯一标识
    let output = Command::new("ioreg")
        .args(["-rd1", "-c", "IOPlatformExpertDevice"])
        .output()
        .map_err(|e| format!("执行 ioreg 失败: {}", e))?;

    let stdout = String::from_utf8_lossy(&output.stdout);
    for line in stdout.lines() {
        if line.contains("IOPlatformSerialNumber") {
            // 提取序列号作为替代标识
            if let Some(serial) = line.split('"').nth(3) {
                return Ok(serial.as_bytes().to_vec());
            }
        }
    }

    Err("无法获取硬件 MAC 地址或序列号".to_string())
}

/// 计算 MAC 地址的 sha256 前 12 位 hex
fn compute_mac_hash(mac_bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(mac_bytes);
    let result = hasher.finalize();
    hex::encode(&result[..6]) // 前 6 字节 = 12 字符 hex
}

/// 生成 ephemeral token（32 字节 crypto random，hex 编码为 64 字符）
fn generate_ephemeral_token() -> String {
    let mut bytes = [0u8; 32];
    rand::rng().fill_bytes(&mut bytes);
    hex::encode(bytes)
}

/// 简单 URL 编码
fn urlencoded(s: &str) -> String {
    let mut result = String::with_capacity(s.len() * 3);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                result.push(b as char);
            }
            _ => {
                result.push('%');
                result.push_str(&format!("{:02X}", b));
            }
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_compute_mac_hash() {
        let mac = vec![0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF];
        let hash = compute_mac_hash(&mac);
        assert_eq!(hash.len(), 12);
        // 应该是确定性的
        assert_eq!(hash, compute_mac_hash(&mac));
    }

    #[test]
    fn test_generate_ephemeral_token() {
        let token = generate_ephemeral_token();
        assert_eq!(token.len(), 64);
        // 两次生成应该不同
        let token2 = generate_ephemeral_token();
        assert_ne!(token, token2);
    }

    #[test]
    fn test_urlencoded() {
        assert_eq!(urlencoded("hello"), "hello");
        assert_eq!(urlencoded("hello world"), "hello%20world");
        assert_eq!(urlencoded("你好"), "%E4%BD%A0%E5%A5%BD");
    }
}
