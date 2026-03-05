## Why

在 Android 与 Mac 之间实现类似 iOS 接力（Handoff）的跨端剪贴板同步，核心场景是验证码的无缝流转。当前市面上的方案要么依赖重量级生态（Apple 全家桶），要么需要复杂的底层配置（Root/ADB），GhostClip 面向有一定动手能力的极客用户，提供一个轻量、免费、自托管的解决方案。

## What Changes

- 新建 Cloudflare Worker 云端中转服务，提供 HTTP API + KV 存储，支持 Token 认证和设备在线感知
- 新建 Android 原生应用（Kotlin），通过无障碍服务实现后台零点击剪贴板捕获，支持局域网直连和云端同步
- 新建 Mac 桌面应用（Tauri/Rust），Menu Bar 常驻，支持自定义快捷键触发发送、自动接收写入剪贴板
- 非对称同步设计：Android → Mac 自动同步，Mac → Android 通过自定义快捷键主动触发
- 局域网直连为主路径（mDNS 发现 + WebSocket），Cloudflare KV 轮询为远程兜底
- Hash 比对防死循环，设备在线感知减少无效轮询

## Capabilities

### New Capabilities
- `cloud-relay`: Cloudflare Worker 云端中转服务，包含 Token 认证、KV 读写、设备在线注册与发现
- `android-sensor`: Android 端剪贴板感知引擎，包含无障碍服务、透明 Activity 读取、WebSocket/HTTP 客户端、后台保活
- `mac-receiver`: Mac 端 Tauri 桌面应用，包含 Menu Bar UI、NSPasteboard 监听与写入、全局自定义快捷键、系统通知
- `lan-discovery`: 局域网设备发现与直连，包含 mDNS/Bonjour 服务注册与发现、局域网 WebSocket 通信
- `sync-protocol`: 跨端同步协议，包含数据格式定义、Hash 去重、设备在线感知状态机、网络切换策略

### Modified Capabilities

（无已有能力需要修改，这是全新项目）

## Impact

- **新增代码库**：三个独立子项目（worker/、android/、mac/）
- **外部依赖**：Cloudflare 账号 + 自定义域名（ghostclip.xkcoding.com）、wrangler CLI
- **Android 权限**：AccessibilityService、INTERNET、FOREGROUND_SERVICE
- **Mac 权限**：辅助功能（全局快捷键）、通知权限
- **运行成本**：$0（完全使用 Cloudflare 免费额度）
