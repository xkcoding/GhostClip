# Changelog

## [0.1.0] - 2026-03-12

GhostClip 首个正式版本。实现 Android <-> Mac 跨设备剪贴板同步。

### Features

- **跨平台剪贴板同步**: Android 与 Mac 之间实时同步剪贴板内容
- **局域网直连**: mDNS 服务发现 + WebSocket 直连，低延迟同步
- **QR 扫码配对**: Mac 端生成 QR 码，Android 扫码一键配对
- **Mac Menu Bar 应用**: Tauri v2 构建，常驻菜单栏，Cmd+Shift+C 快捷键发送
- **Android 前台服务**: 浮球触发同步，通知栏显示连接状态
- **通知交互**: Mac 端 alert 通知含"复制"按钮；Android 端点击通知即复制
- **自动重连**: 断线后自动重连，网络切换自动适配
- **安全配对**: IOKit MAC hash + ephemeral token 鉴权
- **设备名称显示**: 双端通知和状态栏显示对端设备名称
- **同步记录**: Android 端展示剪贴板同步历史

### Architecture

- **Mac**: Tauri v2 + Rust (NSPasteboard, mDNS, WebSocket Server) + Web Frontend
- **Android**: Kotlin + Foreground Service + OkHttp WebSocket Client
- **去重**: MD5 hash pool (LRU, 3s TTL) 双端去重
- **同步方向**: Android -> Mac 自动同步，Mac -> Android 通过快捷键触发

### CI/CD

- GitHub Actions 自动构建 Android APK 和 Mac DMG/.app
- Homebrew Tap 自动发布 Mac 版本
- DMG 自定义背景和图标布局
