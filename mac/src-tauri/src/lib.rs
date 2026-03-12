mod clipboard;
mod cloud_client;
mod hash_pool;
mod mdns;
mod net_monitor;
mod network;
mod pairing;
mod presence;
mod settings;
mod ws_server;

use clipboard::{get_change_count, read_clipboard, write_clipboard};
use cloud_client::CloudError;
use hash_pool::{md5_hash, HashPool};
use network::{ConnectionState, NetworkConfig, NetworkManager};
use pairing::PairingManager;
use settings::Settings;
use std::sync::{
    atomic::{AtomicIsize, AtomicU8, AtomicU64, Ordering},
    Arc, Mutex as StdMutex, OnceLock,
};

/// 网络重启防抖代数计数器，防止连续多次快速重启
static NETWORK_RESTART_GEN: AtomicU64 = AtomicU64::new(0);

/// 记录弹出面板最后隐藏的时间戳（毫秒），用于 toggle 判断
static LAST_POPUP_HIDE_MS: AtomicU64 = AtomicU64::new(0);

/// 全局 AppHandle，供 debug_log 在任意位置发送日志到前端
static APP_HANDLE: OnceLock<tauri::AppHandle> = OnceLock::new();

/// 同时输出到 env_logger 和 Tauri 前端的调试日志
pub(crate) fn debug_log(msg: &str) {
    log::info!("{}", msg);
    if let Some(app) = APP_HANDLE.get() {
        let _ = app.emit(
            "debug-log",
            serde_json::json!({
                "message": msg,
            }),
        );
    }
}
use tauri::{
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    Emitter, Manager, WebviewWindowBuilder,
};
use tokio::sync::mpsc;

/// 应用共享状态
pub struct AppState {
    pub hash_pool: Arc<HashPool>,
    pub last_change_count: AtomicIsize,
    pub network: Arc<tokio::sync::Mutex<Option<Arc<NetworkManager>>>>,
    pub device_id: String,
    pub settings: StdMutex<Settings>,
    /// 缓存的连接状态 (0=Disconnected, 1=Lan, 2=Cloud)
    pub current_conn_state: AtomicU8,
    /// 配对管理器
    pub pairing: Arc<PairingManager>,
    /// WebSocket 服务端口（QR 码 fallback 用）
    pub ws_port: u16,
}

/// 安全截取 UTF-8 字符串（按字符数，不按字节）
fn truncate_str(s: &str, max_chars: usize) -> &str {
    match s.char_indices().nth(max_chars) {
        Some((idx, _)) => &s[..idx],
        None => s,
    }
}

fn conn_state_to_u8(state: ConnectionState) -> u8 {
    match state {
        ConnectionState::Disconnected => 0,
        ConnectionState::Lan => 1,
        ConnectionState::Cloud => 2,
    }
}

fn u8_to_conn_state(v: u8) -> ConnectionState {
    match v {
        1 => ConnectionState::Lan,
        2 => ConnectionState::Cloud,
        _ => ConnectionState::Disconnected,
    }
}

// ============================================
// Tauri Commands
// ============================================

#[tauri::command]
fn cmd_read_clipboard() -> Option<String> {
    read_clipboard()
}

#[tauri::command]
fn cmd_write_clipboard(text: String) -> bool {
    write_clipboard(&text)
}

#[tauri::command]
fn cmd_get_change_count() -> isize {
    get_change_count()
}

#[tauri::command]
fn cmd_md5_hash(text: String) -> String {
    md5_hash(&text)
}

#[tauri::command]
fn cmd_trigger_send(app: tauri::AppHandle) {
    trigger_send(&app);
}

/// 前端请求当前连接状态（用于 popup 显示时补偿错过的事件）
#[tauri::command]
fn cmd_get_connection_state(app: tauri::AppHandle) -> String {
    let state = app.state::<AppState>();
    let cached = state.current_conn_state.load(Ordering::Relaxed);
    let conn = u8_to_conn_state(cached);
    let (state_str, label) = match conn {
        ConnectionState::Lan => ("connected", "局域网直连"),
        ConnectionState::Cloud => ("cloud", "云端同步"),
        ConnectionState::Disconnected => ("disconnected", "未连接"),
    };
    let paired_name = state.pairing.paired_device_name();
    let device_name = if paired_name.is_empty() { "Android".to_string() } else { paired_name };
    serde_json::json!({
        "state": state_str,
        "deviceName": device_name,
        "connectionLabel": label,
    }).to_string()
}

/// 生成 QR 码 SVG 数据
#[tauri::command]
fn cmd_generate_qr_code(app: tauri::AppHandle) -> Result<String, String> {
    let state = app.state::<AppState>();
    let local_ip = get_local_ip().unwrap_or_else(|| "0.0.0.0".to_string());
    let uri = state.pairing.qr_uri(&local_ip, state.ws_port);

    use qrcode::QrCode;
    let code = QrCode::new(uri.as_bytes())
        .map_err(|e| format!("生成 QR 码失败: {}", e))?;

    let svg = code
        .render::<qrcode::render::svg::Color>()
        .min_dimensions(256, 256)
        .max_dimensions(512, 512)
        .quiet_zone(true)
        .build();

    Ok(svg)
}

/// 获取本机局域网 IP（优先非 loopback 的 IPv4）
fn get_local_ip() -> Option<String> {
    use std::net::UdpSocket;
    // 通过 UDP connect 获取出口 IP（不实际发包）
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    let addr = socket.local_addr().ok()?;
    Some(addr.ip().to_string())
}

/// 获取当前配对状态
#[tauri::command]
fn cmd_get_pairing_state(app: tauri::AppHandle) -> String {
    let state = app.state::<AppState>();
    let pairing = &state.pairing;

    let (status, device_name) = match pairing.status() {
        pairing::PairingStatus::WaitingPair => ("waiting_pair", None),
        pairing::PairingStatus::Paired { device_id } => ("paired", Some(device_id)),
    };

    serde_json::json!({
        "status": status,
        "deviceName": device_name,
        "macHash": pairing.mac_hash(),
    }).to_string()
}

/// 解除配对
#[tauri::command]
async fn cmd_unpair(app: tauri::AppHandle) -> Result<(), String> {
    let state = app.state::<AppState>();

    // 向已配对设备发送 unpair 消息并断开
    {
        let net_guard = state.network.lock().await;
        if let Some(ref net) = *net_guard {
            net.send_unpair_and_disconnect().await;
        }
    }

    // 重新生成 token
    state.pairing.regenerate_token();
    state.pairing.set_waiting_pair();

    // 发射连接状态变更事件到前端（解除配对 = 断开连接）
    let _ = app.emit("connection-state-changed", serde_json::json!({
        "state": "disconnected",
        "deviceName": "",
        "connectionLabel": "",
        "error": ""
    }));

    // 发射配对状态变更事件到前端
    let _ = app.emit("pairing-state-changed", serde_json::json!({
        "status": "waiting_pair",
        "deviceName": serde_json::Value::Null,
    }));

    log::info!("用户已解除配对");
    Ok(())
}

#[tauri::command]
fn cmd_quit(app: tauri::AppHandle) {
    log::info!("前端请求退出 GhostClip");
    app.exit(0);
}

#[tauri::command]
fn cmd_update_hotkey(app: tauri::AppHandle, hotkey: String) -> Result<(), String> {
    use tauri_plugin_global_shortcut::GlobalShortcutExt;

    // 注销所有已注册的快捷键
    app.global_shortcut()
        .unregister_all()
        .map_err(|e| format!("注销快捷键失败: {}", e))?;

    // 注册新快捷键
    app.global_shortcut()
        .register(hotkey.as_str())
        .map_err(|e| format!("注册快捷键 {} 失败: {}", hotkey, e))?;

    // 更新设置中的快捷键
    let state = app.state::<AppState>();
    {
        let mut settings = state.settings.lock().unwrap();
        settings.hotkey_raw = hotkey.clone();
    }

    log::info!("全局快捷键已更新: {}", hotkey);
    Ok(())
}

#[tauri::command]
fn cmd_get_settings(app: tauri::AppHandle) -> String {
    let state = app.state::<AppState>();
    let settings = state.settings.lock().unwrap();
    serde_json::to_string(&*settings).unwrap_or_else(|_| "{}".to_string())
}

#[tauri::command]
fn cmd_save_settings(app: tauri::AppHandle, settings: String) -> Result<(), String> {
    let parsed: Settings =
        serde_json::from_str(&settings).map_err(|e| format!("解析设置失败: {}", e))?;

    // 校验云端配置
    if parsed.cloud_enabled {
        if !parsed.cloud_url.is_empty()
            && !parsed.cloud_url.starts_with("http://")
            && !parsed.cloud_url.starts_with("https://")
        {
            return Err("云端 URL 格式无效，需以 http:// 或 https:// 开头".to_string());
        }
        if !parsed.cloud_url.is_empty() && parsed.cloud_token.is_empty() {
            return Err("已填写云端 URL 但 Token 为空，请填写 Token".to_string());
        }
    }

    let state = app.state::<AppState>();

    // 检查云端配置是否变更，决定是否需要重建网络
    let need_network_reinit = {
        let current = state.settings.lock().unwrap();
        current.cloud_url != parsed.cloud_url
            || current.cloud_token != parsed.cloud_token
            || current.cloud_enabled != parsed.cloud_enabled
    };

    {
        let mut current = state.settings.lock().unwrap();
        *current = parsed.clone();
    }

    settings::save_settings(&parsed)?;
    log::info!("设置已保存");

    // 云端配置变更时重建网络（500ms 防抖，避免连续快速重启）
    if need_network_reinit {
        let gen = NETWORK_RESTART_GEN.fetch_add(1, Ordering::SeqCst) + 1;
        log::info!("云端配置已变更，计划重建网络 (gen={})...", gen);
        let network = state.network.clone();
        let app_handle = app.clone();
        tauri::async_runtime::spawn(async move {
            // 防抖等待：500ms 内若有新的重启请求，本次作废
            tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
            if NETWORK_RESTART_GEN.load(Ordering::SeqCst) != gen {
                log::info!("网络重启已被更新的请求覆盖 (gen={}), 跳过", gen);
                return;
            }
            // 先关闭旧的 NetworkManager（abort 后台任务释放端口）
            {
                let mut net_guard = network.lock().await;
                if let Some(ref net) = *net_guard {
                    net.shutdown();
                }
                *net_guard = None;
            }
            // 等待端口释放
            tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
            // 重新启动网络
            start_network(app_handle);
        });
    }

    Ok(())
}

// ============================================
// Core Logic
// ============================================

/// 触发剪贴板发送（快捷键或菜单或前端调用）
fn trigger_send(app: &tauri::AppHandle) {
    let state = app.state::<AppState>();
    if let Some(text) = read_clipboard() {
        match state.hash_pool.check_and_insert(&text) {
            Some(hash) => {
                debug_log(&format!(
                    "发送剪贴板 - 哈希: {}, 长度: {}, 前50字符: {}",
                    hash,
                    text.len(),
                    truncate_str(&text, 50)
                ));

                // 发射 clip-synced 事件到前端
                let _ = app.emit(
                    "clip-synced",
                    serde_json::json!({
                        "text": truncate_str(&text, 200),
                        "direction": "outgoing",
                        "timestamp": timestamp_now(),
                    }),
                );

                let device_id = state.device_id.clone();
                let network = state.network.clone();
                let text_clone = text.clone();
                let app_clone = app.clone();
                tauri::async_runtime::spawn(async move {
                    let net_guard = network.lock().await;
                    if let Some(ref net) = *net_guard {
                        if let Err(e) = net.send_clip(&text_clone, &device_id).await {
                            log::error!("网络发送失败: {}", e);
                            emit_error(&app_clone, &e);
                        }
                    } else {
                        log::warn!("网络未初始化，跳过发送");
                        emit_error(&app_clone, "网络未初始化，请检查设置");
                    }
                });
            }
            None => {
                log::debug!("剪贴板内容重复，跳过发送");
            }
        }
    } else {
        log::debug!("剪贴板为空，跳过发送");
    }
}

/// 启动剪贴板 changeCount 轮询监听
fn start_clipboard_monitor(app_handle: tauri::AppHandle) {
    let state = app_handle.state::<AppState>();
    state
        .last_change_count
        .store(get_change_count(), Ordering::Relaxed);

    std::thread::spawn(move || loop {
        std::thread::sleep(std::time::Duration::from_millis(500));

        let current = get_change_count();
        let state = app_handle.state::<AppState>();
        let last = state.last_change_count.load(Ordering::Relaxed);

        if current != last {
            state.last_change_count.store(current, Ordering::Relaxed);

            if let Some(text) = read_clipboard() {
                let hash = md5_hash(&text);
                if state.hash_pool.contains(&hash) {
                    log::debug!("剪贴板变化（已知内容），哈希: {}", hash);
                } else {
                    log::info!("剪贴板变化（新内容）- 哈希: {}, 长度: {}", hash, text.len());
                }
            }
        }
    });
}

/// 生成设备 ID
fn generate_device_id() -> String {
    let hostname = hostname::get()
        .map(|h| h.to_string_lossy().to_string())
        .unwrap_or_else(|_| "unknown-mac".to_string());
    let hash = md5_hash(&format!("ghostclip-mac-{}", hostname));
    format!("mac-{}", &hash[..12])
}

/// 发送系统通知（带「复制」按钮，alert 样式停留直到用户操作）
fn send_notification(_app: &tauri::AppHandle, text: &str) {
    let text_owned = text.to_string();
    std::thread::spawn(move || {
        use mac_notification_sys::{Notification, MainButton, NotificationResponse, set_application};
        // 绑定 GhostClip bundle id，避免以 Finder 身份发通知导致 Choose Application 弹窗
        let _ = set_application("com.xkcoding.ghostclip");
        let preview = truncate_str(&text_owned, 100);
        let result = Notification::new()
            .title("来自 Android 的剪贴板已同步")
            .message(&preview)
            .main_button(MainButton::SingleAction("复制"))
            .send();
        match result {
            Ok(response) => {
                match response {
                    NotificationResponse::ActionButton(_) | NotificationResponse::Click => {
                        // 用户点击「复制」或点击通知本体 → 写入剪贴板
                        clipboard::write_clipboard(&text_owned);
                        log::info!("通知点击复制: len={}", text_owned.len());
                    }
                    _ => {}
                }
            }
            Err(e) => log::error!("发送通知失败: {}", e),
        }
    });
}

/// 发射连接状态变更事件到前端
fn emit_connection_state(app: &tauri::AppHandle, conn_state: ConnectionState) {
    emit_connection_state_with_error(app, conn_state, None);
}

/// 发射连接状态变更事件到前端（可携带错误信息）
fn emit_connection_state_with_error(
    app: &tauri::AppHandle,
    conn_state: ConnectionState,
    error: Option<&str>,
) {
    // 缓存连接状态到 AppState
    if let Some(app_state) = app.try_state::<AppState>() {
        app_state.current_conn_state.store(conn_state_to_u8(conn_state), Ordering::Relaxed);
    }

    let (state_str, label) = match conn_state {
        ConnectionState::Lan => ("connected", "局域网直连"),
        ConnectionState::Cloud => ("cloud", "云端同步"),
        ConnectionState::Disconnected => ("disconnected", "未连接"),
    };

    // 使用真实的已配对设备名
    let device_name = if let Some(app_state) = app.try_state::<AppState>() {
        let name = app_state.pairing.paired_device_name();
        if name.is_empty() { "Android".to_string() } else { name }
    } else {
        "Android".to_string()
    };

    let mut payload = serde_json::json!({
        "state": state_str,
        "deviceName": device_name,
        "connectionLabel": label,
    });

    if let Some(err_msg) = error {
        payload["error"] = serde_json::Value::String(err_msg.to_string());
    }

    let _ = app.emit("connection-state-changed", payload);

    // 更新系统托盘 Tooltip
    let tooltip = if let Some(err_msg) = error {
        format!("GhostClip · {} ({})", label, err_msg)
    } else {
        format!("GhostClip · {}", label)
    };
    if let Some(tray) = app.tray_by_id("main") {
        let _ = tray.set_tooltip(Some(&tooltip));
    }
}

/// 发射错误事件到前端
fn emit_error(app: &tauri::AppHandle, message: impl std::fmt::Display) {
    let msg = message.to_string();
    log::warn!("向前端报告错误: {}", msg);
    let _ = app.emit(
        "error-occurred",
        serde_json::json!({
            "message": msg,
            "timestamp": timestamp_now(),
        }),
    );
}

/// 启动网络服务
fn start_network(app_handle: tauri::AppHandle) {
    let state = app_handle.state::<AppState>();
    let hash_pool = state.hash_pool.clone();
    let device_id = state.device_id.clone();
    let network_state = state.network.clone();

    // 从设置中读取云端配置
    let pairing = state.pairing.clone();
    let (cloud_url, cloud_token, cloud_enabled) = {
        let settings = state.settings.lock().unwrap();
        (
            settings.cloud_url.clone(),
            settings.cloud_token.clone(),
            settings.cloud_enabled,
        )
    };

    tauri::async_runtime::spawn(async move {
        let (receive_tx, mut receive_rx) = mpsc::channel::<String>(32);
        let (error_tx, mut error_rx) = mpsc::channel::<CloudError>(16);

        let config = NetworkConfig {
            ws_port: 9876,
            cloud_url: if cloud_enabled && !cloud_url.is_empty() {
                Some(cloud_url)
            } else {
                None
            },
            cloud_token: if cloud_enabled && !cloud_token.is_empty() {
                Some(cloud_token)
            } else {
                None
            },
            device_id,
            pairing,
        };

        match NetworkManager::start(config, hash_pool, receive_tx, error_tx).await {
            Ok(net) => {
                let conn_state = net.connection_state();
                let mut state_rx = net.subscribe_connection_state();
                let net = Arc::new(net);
                {
                    let mut net_guard = network_state.lock().await;
                    *net_guard = Some(net.clone());
                }
                debug_log("网络服务已启动");
                emit_connection_state(&app_handle, conn_state);

                // 监听云端错误：auth 失败时切换到 Disconnected + 通知前端
                let app_for_errors = app_handle.clone();
                let net_for_errors = net.clone();
                tokio::spawn(async move {
                    while let Some(error) = error_rx.recv().await {
                        emit_error(&app_for_errors, &error);

                        // Auth 失败 → 强制切 Disconnected 并携带错误信息
                        if matches!(error, CloudError::AuthFailed(_)) {
                            net_for_errors
                                .set_connection_state(ConnectionState::Disconnected);
                            emit_connection_state_with_error(
                                &app_for_errors,
                                ConnectionState::Disconnected,
                                Some("Token 无效或已过期，请在设置中更新"),
                            );
                        }
                    }
                });

                // 监听连接状态变更，自动通知前端（含配对状态）
                let app_for_state = app_handle.clone();
                let net_for_state = net.clone();
                tokio::spawn(async move {
                    while state_rx.changed().await.is_ok() {
                        let new_state = *state_rx.borrow();
                        debug_log(&format!("连接状态变更: {}", new_state));
                        emit_connection_state(&app_for_state, new_state);

                        // 同步发射配对状态变更事件
                        let pairing = net_for_state.pairing();
                        let (status, device_name) = if pairing.is_paired() {
                            ("paired", pairing.paired_device_id())
                        } else {
                            ("waiting_pair", None)
                        };
                        let _ = app_for_state.emit("pairing-state-changed", serde_json::json!({
                            "status": status,
                            "deviceName": device_name,
                        }));
                    }
                });
            }
            Err(e) => {
                log::error!("网络服务启动失败: {}", e);
                emit_connection_state_with_error(
                    &app_handle,
                    ConnectionState::Disconnected,
                    Some(&format!("网络启动失败: {}", e)),
                );
                return;
            }
        }

        // 处理接收到的远端剪贴板内容
        while let Some(text) = receive_rx.recv().await {
            let state = app_handle.state::<AppState>();
            state.hash_pool.check_and_insert(&text);

            if write_clipboard(&text) {
                state
                    .last_change_count
                    .store(get_change_count(), Ordering::Relaxed);
                debug_log(&format!(
                    "远端剪贴板已写入本地, 前50字符: {}",
                    truncate_str(&text, 50)
                ));

                // 发射 clip-synced 事件
                let _ = app_handle.emit(
                    "clip-synced",
                    serde_json::json!({
                        "text": truncate_str(&text, 200),
                        "direction": "incoming",
                        "timestamp": timestamp_now(),
                    }),
                );

                // 发送系统通知（如果启用）
                let notify_enabled = {
                    let settings = state.settings.lock().unwrap();
                    settings.notifications_enabled
                };
                if notify_enabled {
                    send_notification(&app_handle, &text);
                }
            } else {
                log::error!("写入本地剪贴板失败");
            }
        }
    });
}

/// 隐藏弹出面板并记录时间戳（供前端失焦时调用）
#[tauri::command]
fn cmd_popup_hide(app: tauri::AppHandle) {
    if let Some(win) = app.get_webview_window("main") {
        let _ = win.hide();
    }
    record_popup_hide_time();
}

fn record_popup_hide_time() {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64;
    LAST_POPUP_HIDE_MS.store(now, Ordering::Relaxed);
}

/// 切换弹出面板的显示/隐藏
fn toggle_popup(app: &tauri::AppHandle, rect: tauri::Rect) {
    let Some(win) = app.get_webview_window("main") else {
        return;
    };

    if win.is_visible().unwrap_or(false) {
        let _ = win.hide();
        record_popup_hide_time();
        return;
    }

    // 如果刚刚被失焦隐藏（<500ms），视为 toggle-off，不再显示
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64;
    let last_hide = LAST_POPUP_HIDE_MS.load(Ordering::Relaxed);
    if now.saturating_sub(last_hide) < 500 {
        return;
    }

    // 将弹出窗口定位到托盘图标下方居中（tray rect 为物理坐标）
    let scale = win.scale_factor().unwrap_or(2.0);
    let popup_w = 360.0_f64;

    if let (tauri::Position::Physical(pos), tauri::Size::Physical(size)) =
        (rect.position, rect.size)
    {
        let x = (pos.x as f64 + size.width as f64 / 2.0) / scale - popup_w / 2.0;
        let y = (pos.y as f64 + size.height as f64) / scale;

        let _ = win.set_position(tauri::LogicalPosition::new(x, y));
    }

    // 确保显示 dropdown 视图（防止上次残留 settings 状态）
    let _ = app.emit_to("main", "show-dropdown", ());

    let _ = win.show();
    let _ = win.set_focus();

    // 重新发射缓存的连接状态（补偿 popup 隐藏期间错过的事件）
    if let Some(app_state) = app.try_state::<AppState>() {
        let cached = app_state.current_conn_state.load(Ordering::Relaxed);
        let conn = u8_to_conn_state(cached);
        emit_connection_state(app, conn);
    }
}

#[tauri::command]
fn cmd_open_settings(app: tauri::AppHandle) {
    // 先隐藏弹出面板
    if let Some(popup) = app.get_webview_window("main") {
        let _ = popup.hide();
    }
    show_settings_window(&app);
}

/// 打开或聚焦设置窗口
fn show_settings_window(app: &tauri::AppHandle) {
    // 如果窗口已存在，确保显示设置视图并聚焦
    if let Some(win) = app.get_webview_window("settings") {
        let _ = app.emit_to("settings", "show-settings", ());
        let _ = win.show();
        let _ = win.set_focus();
        return;
    }
    // 创建新的设置窗口
    match WebviewWindowBuilder::new(app, "settings", tauri::WebviewUrl::App("index.html#settings".into()))
        .title("GhostClip 设置")
        .inner_size(480.0, 600.0)
        .resizable(false)
        .maximizable(false)
        .build()
    {
        Ok(win) => {
            let _ = win.show();
            let _ = win.set_focus();
        }
        Err(e) => log::error!("创建设置窗口失败: {}", e),
    }
}

fn timestamp_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64
}

/// 启动 WiFi 网络变更监控
///
/// WiFi 变更时：踢掉已配对设备 → 重新生成 token → 状态回到 WAITING_PAIR → 重建网络
fn start_net_monitor(app_handle: tauri::AppHandle) {
    let (_monitor, mut rx) = net_monitor::NetMonitor::start();
    // 保持 monitor 的所有权（不被 drop），通过 leak 延长生命周期
    // NetMonitor 会在 App 退出时自然销毁
    std::mem::forget(_monitor);

    tauri::async_runtime::spawn(async move {
        while let Some(event) = rx.recv().await {
            debug_log(&format!("WiFi 网络变更检测: SSID={:?}", event.ssid));

            let state = app_handle.state::<AppState>();

            // 踢掉已配对设备
            {
                let net_guard = state.network.lock().await;
                if let Some(ref net) = *net_guard {
                    if net.pairing().is_paired() {
                        net.send_unpair_and_disconnect().await;
                        debug_log("WiFi 变更：已踢掉配对设备");
                    }
                }
            }

            // 重新生成 token
            state.pairing.regenerate_token();
            state.pairing.set_waiting_pair();

            // 通知前端配对状态变更
            let _ = app_handle.emit("pairing-state-changed", serde_json::json!({
                "status": "waiting_pair",
                "deviceName": serde_json::Value::Null,
            }));

            // 重建网络（500ms 防抖）
            let gen = NETWORK_RESTART_GEN.fetch_add(1, Ordering::SeqCst) + 1;
            debug_log(&format!("WiFi 变更触发网络重建 (gen={})", gen));
            let network = state.network.clone();
            let app_clone = app_handle.clone();
            tokio::spawn(async move {
                tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
                if NETWORK_RESTART_GEN.load(Ordering::SeqCst) != gen {
                    return;
                }
                {
                    let mut net_guard = network.lock().await;
                    if let Some(ref net) = *net_guard {
                        net.shutdown();
                    }
                    *net_guard = None;
                }
                tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
                start_network(app_clone);
            });
        }
    });
}

// ============================================
// App Entry
// ============================================

pub fn run() {
    env_logger::init();

    let device_id = generate_device_id();
    log::info!("设备 ID: {}", device_id);

    let loaded_settings = settings::load_settings();
    let initial_hotkey = loaded_settings.hotkey_raw.clone();
    log::info!("已加载设置，快捷键: {}", initial_hotkey);

    // 初始化配对管理器
    let pairing = PairingManager::new().expect("初始化配对管理器失败");

    let state = AppState {
        hash_pool: Arc::new(HashPool::new()),
        last_change_count: AtomicIsize::new(0),
        network: Arc::new(tokio::sync::Mutex::new(None)),
        device_id,
        settings: StdMutex::new(loaded_settings),
        current_conn_state: AtomicU8::new(0), // Disconnected
        pairing,
        ws_port: 9876,
    };

    tauri::Builder::default()
        .manage(state)
        .plugin(tauri_plugin_notification::init())
        .setup(move |app| {
            // 保存全局 AppHandle
            APP_HANDLE.set(app.handle().clone()).ok();

            // 注册全局快捷键插件（必须在 setup 内通过 handle 注册，避免 config 反序列化问题）
            use tauri_plugin_global_shortcut::GlobalShortcutExt;
            app.handle().plugin(
                tauri_plugin_global_shortcut::Builder::new()
                    .with_handler(|app, shortcut, event| {
                        if event.state == tauri_plugin_global_shortcut::ShortcutState::Pressed {
                            log::info!("全局快捷键触发: {}", shortcut);
                            trigger_send(app);
                        }
                    })
                    .build(),
            )?;
            app.global_shortcut().register(initial_hotkey.as_str())?;
            log::info!("已注册全局快捷键: {}", initial_hotkey);

            // 隐藏 Dock 图标
            #[cfg(target_os = "macos")]
            {
                app.set_activation_policy(tauri::ActivationPolicy::Accessory);
            }

            // 构建系统托盘（左键点击弹出自定义面板）
            let tray_icon = tauri::image::Image::from_bytes(include_bytes!("../resources/tray_icon.png"))?;
            let tray = TrayIconBuilder::with_id("main")
                .icon(tray_icon)
                .icon_as_template(true)
                .tooltip("GhostClip · 未连接")
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        rect,
                        ..
                    } = event
                    {
                        toggle_popup(tray.app_handle(), rect);
                    }
                })
                .build(app)?;
            app.manage(tray);

            // 启动剪贴板 changeCount 监听
            start_clipboard_monitor(app.handle().clone());

            // 启动网络服务
            start_network(app.handle().clone());

            // 启动 WiFi 网络变更监控
            start_net_monitor(app.handle().clone());

            log::info!("GhostClip 已启动 - Menu Bar 模式");
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            cmd_read_clipboard,
            cmd_write_clipboard,
            cmd_get_change_count,
            cmd_md5_hash,
            cmd_trigger_send,
            cmd_get_connection_state,
            cmd_generate_qr_code,
            cmd_get_pairing_state,
            cmd_unpair,
            cmd_quit,
            cmd_update_hotkey,
            cmd_get_settings,
            cmd_save_settings,
            cmd_open_settings,
            cmd_popup_hide,
        ])
        .run(tauri::generate_context!())
        .expect("启动 GhostClip 失败");
}
