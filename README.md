# GhostClip

Android 与 Mac 之间的跨端剪贴板同步工具。在一台设备上复制文字，另一台设备上直接粘贴。

## 特性

- **局域网直连**：同一 WiFi 下通过 mDNS 自动发现 + WebSocket 直连，延迟 <10ms
- **QR 扫码配对**：Mac 端生成 QR 码，Android 扫码一键配对，安全便捷
- **非对称同步**：Android -> Mac 自动同步，Mac -> Android 通过 Cmd+Shift+C 触发
- **通知交互**：收到剪贴板时弹出通知，点击或按"复制"即可写入剪贴板
- **自动重连**：断线后自动重连，网络切换自动适配
- **Menu Bar 应用**：Mac 端常驻菜单栏，不占用 Dock

## 架构

```
┌─────────────┐             ┌──────────────────┐
│  Android App│◄────LAN────►│  Mac Tauri App   │
│  (Kotlin)   │   mDNS +    │  (Rust + Web UI) │
│             │  WebSocket   │                  │
└─────────────┘             └──────────────────┘
```

| 组件 | 目录 | 技术栈 |
|------|------|--------|
| Mac 端 | `mac/` | Tauri v2 (Rust 后端 + Web 前端) |
| Android 端 | `android/` | Kotlin + Foreground Service |

## 安装

### Mac 端

**方式一：Homebrew**

```bash
brew install --cask xkcoding/tap/ghostclip
```

**方式二：手动安装**

1. 从 [Releases](../../releases) 页面下载最新的 `.dmg` 文件
2. 打开 DMG，将 GhostClip 拖入应用程序文件夹
3. 首次启动时，在系统设置中允许运行

### Android 端

1. 从 [Releases](../../releases) 页面下载最新的 `.apk` 文件
2. 在手机上安装 APK（需允许"安装未知来源应用"）

## 使用方法

### 配对

1. 确保 Android 和 Mac 在同一 WiFi 下
2. Mac 端点击 Menu Bar 图标 -> 设置 -> 扫码配对，显示 QR 码
3. Android 端打开 GhostClip -> 扫码配对，扫描 QR 码
4. 配对成功后自动建立连接

### Android -> Mac（自动同步）

1. 在 Android 任意应用中复制文字
2. 点击悬浮球/快捷方式打开 GhostClip
3. App 自动读取剪贴板并发送到 Mac
4. Mac 端自动接收并写入剪贴板，直接 `Cmd+V` 粘贴

### Mac -> Android（快捷键触发）

1. 在 Mac 上复制文字（`Cmd+C`）
2. 按下发送快捷键（默认 `Cmd+Shift+C`）
3. Android 端收到通知，点击通知或"复制"按钮即可写入剪贴板

## 技术说明

- **去重机制**：两端各维护 MD5 Hash 池（LRU，3 秒 TTL），防止同步死循环
- **安全配对**：IOKit MAC hash + ephemeral token 鉴权，WebSocket 连接需携带 token
- **仅限文本**：当前版本仅支持纯文本同步，不支持图片或文件

## License

MIT
