# Changelog

## [0.1.1] - 2026-03-12

### Fixed

- **Mac 通知弹窗修复**: 首次接收 Android 剪贴板时不再弹出 "Choose Application" 对话框
  - 原因：mac-notification-sys 默认以 Finder 身份发通知，点击按钮时 macOS 不知道该激活哪个应用
  - 修复：通知发送前绑定 GhostClip 的 bundle identifier (com.xkcoding.ghostclip)
- **Mac 安装安全弹窗**: Homebrew 安装后不再弹出 macOS Gatekeeper 安全警告
  - Cask 配置新增 postflight 自动移除 quarantine 隔离标记
- **Mac 托盘图标偏小**: Menu Bar 图标放大约 14%，视觉更清晰

### Improved

- **Mac 多芯片支持**: DMG 改为 Universal Binary，同时支持 Apple Silicon (M1/M2/M3) 和 Intel Mac
- **README 完善**: 新增产品截图、三层架构图、安装说明、使用方式等章节
- **项目规范化**: 添加 MIT LICENSE、Landing Page 截图预览组件

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
