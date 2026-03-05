use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

/// 应用设置
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub hotkey: String,
    pub hotkey_raw: String,
    pub cloud_url: String,
    pub cloud_token: String,
    pub cloud_enabled: bool,
    pub notifications_enabled: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            hotkey: "\u{2318} \u{21E7} C".to_string(),
            hotkey_raw: "CmdOrCtrl+Shift+C".to_string(),
            cloud_url: String::new(),
            cloud_token: String::new(),
            cloud_enabled: true,
            notifications_enabled: true,
        }
    }
}

/// 获取设置文件路径
fn settings_path() -> PathBuf {
    let mut path = dirs::config_dir().unwrap_or_else(|| PathBuf::from("."));
    path.push("com.xkcoding.ghostclip");
    fs::create_dir_all(&path).ok();
    path.push("settings.json");
    path
}

/// 从磁盘加载设置
pub fn load_settings() -> Settings {
    let path = settings_path();
    match fs::read_to_string(&path) {
        Ok(content) => serde_json::from_str(&content).unwrap_or_default(),
        Err(_) => Settings::default(),
    }
}

/// 保存设置到磁盘
pub fn save_settings(settings: &Settings) -> Result<(), String> {
    let path = settings_path();
    let json = serde_json::to_string_pretty(settings)
        .map_err(|e| format!("序列化设置失败: {}", e))?;
    fs::write(&path, json).map_err(|e| format!("写入设置文件失败: {}", e))?;
    log::info!("设置已保存到 {}", path.display());
    Ok(())
}
