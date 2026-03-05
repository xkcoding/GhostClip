## Context

GhostClip 是一个全新项目，目标是在 Android 与 Mac 之间实现跨端剪贴板同步。项目面向极客用户，需要用户自行部署 Cloudflare Worker 并配置域名。当前不存在任何已有代码或基础设施。

约束条件：
- Android 16 + HyperOS 3.0（小米）为主要测试目标
- Cloudflare Workers 免费额度（100k 请求/天，1k KV 写入/天，100k KV 读取/天）
- 不上架 Google Play，APK 直装
- 不需要历史剪贴板同步，错过即丢弃

## Goals / Non-Goals

**Goals:**
- Android 端"复制+一键同步"体验（通过悬浮球/快捷方式触发，前台自动读取+投递）
- Mac 端自定义快捷键主动触发发送，自动接收
- 同一 WiFi 下局域网直连（低延迟 <10ms）
- 不同网络通过 Cloudflare 云端中转（延迟 ~3s）
- 完全使用 Cloudflare 免费额度，运行成本 $0
- 设备在线感知，单点在线时不做无效轮询

**Non-Goals:**
- 不支持图片/文件同步，仅限纯文本
- 不做应用层加密（依赖 TLS/HTTPS + 局域网加密）
- 不支持剪贴板历史回溯
- 不上架任何应用商店
- 不支持 iOS/Windows/Linux（MVP 阶段）
- 不做自动更新机制

## Decisions

### 1. Android 端剪贴板捕获：悬浮球手动触发 + 前台自动读取

**选择**: 用户通过系统悬浮球/快捷方式打开 App，App 在 onResume 自动读取 ClipboardManager

**Spike 验证结果** (Android 16 + HyperOS 3.0, Xiaomi 16 Pro):
- AccessibilityService 事件文本匹配: **失败** — HyperOS 3.0 不触发相关 accessibility event
- ClipboardManager.addPrimaryClipChangedListener(): **失败** — 后台 Service 中注册的监听器不回调
- 透明 Activity 前台读取 ClipboardManager: **成功** — 前台 Activity 可正常读取
- 结论: 后台自动捕获在 HyperOS 3.0 上不可行，采用手动触发方案

**用户流程**: 复制文字 → 点击悬浮球 → App 打开自动读取+投递 → 自动退回
- 正常点击图标: 进入主界面（设置、历史等）
- 悬浮球/快捷方式启动: 带 Intent Extra(auto_sync=true) → 读取+投递+finish()

**被否决的方案**:
- Shizuku/ADB 提权：需开发者模式，违背"不折腾"定位
- 系统分享菜单/Quick Tile：与微信文件传输助手无本质区别
- 自定义输入法 (IME)：要求切换输入法，体验过重
- NotificationListenerService：覆盖面窄（仅通知类内容）
- 后台 AccessibilityService 自动捕获：HyperOS 3.0 验证失败

### 2. Mac 端技术栈：Tauri v2 (Rust + Web UI)

**选择**: Tauri v2

**替代方案**:
- 原生 Swift/AppKit：性能最优但开发成本高，UI 开发慢
- Electron：跨平台但内存占用大（100MB+），不适合后台常驻
- Flutter Desktop：不够成熟，macOS 系统 API 绑定不完善

**理由**: Tauri 打包体积小（~5MB），内存占用低，Rust 后端可直接调用 macOS 系统 API（NSPasteboard、全局快捷键），Web 前端快速实现配置界面。

### 3. 网络拓扑：局域网直连 + 云端 KV 轮询

**选择**: Mac 做局域网 WebSocket Server（mDNS 注册），Android 做 Client；云端走 Cloudflare KV + HTTP 轮询

**替代方案**:
- 纯云端 WebSocket（Durable Objects）：实时性最佳但需要 $5/月付费计划
- 纯局域网：不同网络时完全不可用
- 第三方实时服务（Supabase/Ably）：引入额外依赖和风控风险

**理由**: 日常使用同一 WiFi 走局域网（延迟 <10ms），不同网络时走云端轮询（延迟 ~3s），完全 $0。Mac 做 Server 因为桌面设备通常常开，Android 作为移动设备做 Client 更自然。

### 4. 云端轮询策略：设备在线感知 + 固定 3s 间隔

**选择**: 设备通过 KV 注册在线状态，仅当有对端在线时才启用 3s 固定间隔轮询

**状态机**:
- IDLE 模式（无对端）：每 30s GET /peers 检查是否有对端上线，不写心跳（自身 online 记录 15min TTL 后自然过期）
- POLL 模式（有对端）：每 3s GET /clip 轮询新数据，每 10min PUT /register 续命心跳

**KV 写入预算（1,000/天）**:
- 心跳写入：2 设备 × 6次/小时 × 8小时 ≈ 96
- 剪贴板写入：~100 次/天
- 状态切换写入：~10 次/天
- 合计 ~206 次/天，远低于上限

### 5. 同步方向：非对称设计

**选择**: Android → Mac 自动同步，Mac → Android 需按自定义快捷键（默认 Cmd+Shift+C）

**理由**: Mac 端复制频率极高（代码、文章等），如果双向自动同步会导致 Android 剪贴板被大量无关内容污染。非对称设计让验证码等高价值内容自动流转，而 Mac → Android 仅在用户明确意图时触发。

### 6. 防死循环：Hash 比对

**选择**: 两端各维护内存中的 LRU Hash 池（MD5），3 秒有效期，发送和接收前比对

**理由**: 无状态设计，两端无需知道对方存在，只需关注本地"记忆"。简单可靠。

### 7. 认证方式：部署时配置 Token

**选择**: Worker 部署时通过 `wrangler secret put GHOST_TOKEN` 设置密钥，客户端请求携带 `Authorization: Bearer <token>`

**理由**: 极简认证，无需用户管理系统，适合个人自托管场景。

## Risks / Trade-offs

- **[已验证] Android 16 后台剪贴板捕获失败** → 已确认 HyperOS 3.0 上 AccessibilityService + ClipboardManager 后台监听均不可用；已降级为悬浮球手动触发方案，前台读取 100% 可靠
- **[中] HyperOS 后台杀进程** → 降级方案不再依赖后台 Service 常驻，风险降低；仍保留 Foreground Notification 用于接收端推送场景
- **[中] Cloudflare KV 最终一致性** → 轮询场景可接受微秒级延迟，不影响功能正确性
- **[中] 局域网 mDNS 在部分网络环境下不可用**（如公司网络隔离） → 自动回退云端，并在 UI 中支持手动输入 Mac IP 地址
- **[低] 单一 Token 无法区分设备** → MVP 阶段足够，未来可扩展为 per-device token
- **[低] 无应用层加密** → 局域网有 WPA 加密，云端有 HTTPS，对个人使用场景风险可控；未来可叠加 AES-256-GCM
