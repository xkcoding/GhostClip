use reqwest::Client;
use serde::{Deserialize, Serialize};

/// 云端错误类型
#[derive(Debug, Clone)]
pub enum CloudError {
    /// Token 校验失败 (401/403)
    AuthFailed(String),
    /// 网络请求失败（连接超时、DNS 解析等）
    NetworkError(String),
    /// 服务端错误 (5xx)
    ServerError(String),
    /// 其他错误（解析失败等）
    Other(String),
}

impl std::fmt::Display for CloudError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CloudError::AuthFailed(msg) => write!(f, "认证失败: {}", msg),
            CloudError::NetworkError(msg) => write!(f, "网络错误: {}", msg),
            CloudError::ServerError(msg) => write!(f, "服务端错误: {}", msg),
            CloudError::Other(msg) => write!(f, "{}", msg),
        }
    }
}

impl From<CloudError> for String {
    fn from(e: CloudError) -> String {
        e.to_string()
    }
}

/// Worker API 的剪贴板记录
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipRecord {
    pub device_id: String,
    pub text: String,
    pub hash: String,
    pub timestamp: u64,
}

/// Worker API 的设备记录
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerRecord {
    pub device_id: String,
    pub device_type: String,
}

/// POST /clip 响应
#[derive(Debug, Deserialize)]
pub struct PostClipResponse {
    pub status: String,
    pub hash: String,
}

/// GET /peers 响应
#[derive(Debug, Deserialize)]
pub struct PeersResponse {
    pub peers: Vec<PeerRecord>,
}

/// PUT /register 响应
#[derive(Debug, Deserialize)]
#[allow(dead_code)]
pub struct RegisterResponse {
    pub status: String,
}

/// 云端 HTTP 客户端
pub struct CloudClient {
    client: Client,
    base_url: String,
    token: String,
    device_id: String,
}

impl CloudClient {
    pub fn new(base_url: String, token: String, device_id: String) -> Self {
        Self {
            client: Client::new(),
            base_url: base_url.trim_end_matches('/').to_string(),
            token,
            device_id,
        }
    }

    /// 将 HTTP 状态码分类为 CloudError
    fn classify_status(endpoint: &str, status: reqwest::StatusCode) -> CloudError {
        if status == reqwest::StatusCode::UNAUTHORIZED || status == reqwest::StatusCode::FORBIDDEN {
            CloudError::AuthFailed(format!("{} 返回 {} - Token 无效或已过期", endpoint, status))
        } else if status.is_server_error() {
            CloudError::ServerError(format!("{} 返回 {}", endpoint, status))
        } else {
            CloudError::Other(format!("{} 返回 {}", endpoint, status))
        }
    }

    /// 将 reqwest 错误分类为 CloudError
    fn classify_reqwest(endpoint: &str, e: reqwest::Error) -> CloudError {
        if e.is_connect() || e.is_timeout() {
            CloudError::NetworkError(format!("{} 请求失败: {}", endpoint, e))
        } else {
            CloudError::Other(format!("{} 请求失败: {}", endpoint, e))
        }
    }

    /// POST /clip - 发送剪贴板内容到云端
    pub async fn post_clip(
        &self,
        text: &str,
        hash: &str,
    ) -> Result<PostClipResponse, CloudError> {
        let url = format!("{}/clip", self.base_url);
        let body = serde_json::json!({
            "device_id": self.device_id,
            "text": text,
            "hash": hash,
            "timestamp": std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_millis() as u64,
        });

        let resp = self
            .client
            .post(&url)
            .bearer_auth(&self.token)
            .json(&body)
            .send()
            .await
            .map_err(|e| Self::classify_reqwest("POST /clip", e))?;

        if !resp.status().is_success() {
            return Err(Self::classify_status("POST /clip", resp.status()));
        }

        resp.json::<PostClipResponse>()
            .await
            .map_err(|e| CloudError::Other(format!("POST /clip 解析响应失败: {}", e)))
    }

    /// GET /clip - 轮询最新剪贴板内容
    /// 返回 None 表示无新内容（304 或相同 hash）
    pub async fn get_clip(
        &self,
        last_hash: Option<&str>,
    ) -> Result<Option<ClipRecord>, CloudError> {
        let mut url = format!("{}/clip?device_id={}", self.base_url, self.device_id);
        if let Some(hash) = last_hash {
            url = format!("{}&last_hash={}", url, hash);
        }

        let resp = self
            .client
            .get(&url)
            .bearer_auth(&self.token)
            .send()
            .await
            .map_err(|e| Self::classify_reqwest("GET /clip", e))?;

        if resp.status().as_u16() == 304 {
            return Ok(None);
        }

        if !resp.status().is_success() {
            return Err(Self::classify_status("GET /clip", resp.status()));
        }

        let record = resp
            .json::<ClipRecord>()
            .await
            .map_err(|e| CloudError::Other(format!("GET /clip 解析响应失败: {}", e)))?;

        Ok(Some(record))
    }

    /// PUT /register - 注册设备在线状态
    pub async fn register(&self) -> Result<RegisterResponse, CloudError> {
        let url = format!("{}/register", self.base_url);
        let body = serde_json::json!({
            "device_id": self.device_id,
            "device_type": "mac",
        });

        let resp = self
            .client
            .put(&url)
            .bearer_auth(&self.token)
            .json(&body)
            .send()
            .await
            .map_err(|e| Self::classify_reqwest("PUT /register", e))?;

        if !resp.status().is_success() {
            return Err(Self::classify_status("PUT /register", resp.status()));
        }

        resp.json::<RegisterResponse>()
            .await
            .map_err(|e| CloudError::Other(format!("PUT /register 解析响应失败: {}", e)))
    }

    /// GET /peers - 获取在线设备列表
    pub async fn get_peers(&self) -> Result<Vec<PeerRecord>, CloudError> {
        let url = format!("{}/peers", self.base_url);

        let resp = self
            .client
            .get(&url)
            .bearer_auth(&self.token)
            .send()
            .await
            .map_err(|e| Self::classify_reqwest("GET /peers", e))?;

        if !resp.status().is_success() {
            return Err(Self::classify_status("GET /peers", resp.status()));
        }

        let peers_resp = resp
            .json::<PeersResponse>()
            .await
            .map_err(|e| CloudError::Other(format!("GET /peers 解析响应失败: {}", e)))?;

        Ok(peers_resp.peers)
    }

    pub fn device_id(&self) -> &str {
        &self.device_id
    }
}
