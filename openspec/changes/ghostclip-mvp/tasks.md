## 0. Phase 0: 技术 Spike 验证

- [x] 0.1 创建最小 Android 项目，注册 AccessibilityService，验证能否检测到其他 App 的复制事件（Android 16 + HyperOS 3.0）
  - **结果: 失败**。AccessibilityService 事件文本匹配（"复制"/"copy"）在 HyperOS 3.0 上不触发；ClipboardManager.addPrimaryClipChangedListener() 在后台同样无法触发回调
- [x] 0.2 实现透明 Activity（0dp x 0dp）闪现读取 ClipboardManager，验证能否在后台成功获取剪贴板文本
  - **结果: 部分成功**。透明 Activity 本身可以读取剪贴板，但由 AccessibilityService 后台启动时不触发（因为 0.1 的监听不触发）；前台 onResume 时直接读取 ClipboardManager 100% 成功
- [x] 0.3 降级方案验证
  - **结论: 采用 Plan D — 悬浮球手动触发**。用户复制文字后，点击小米悬浮球打开 App，App 在 onResume 自动读取剪贴板内容。多一步点击但 100% 可靠
  - Plan B (NotificationListener) / Plan C (IME) 暂不验证，悬浮球方案体验可接受
  - **后续 MVP 设计**: 正常点击图标 → 进入主界面；悬浮球/快捷方式带 Intent Extra(auto_sync=true) → 读取+投递+自动退回
  - **最终验证**: onResume + onWindowFocusChanged(200ms delay) 前台读取方案在 Xiaomi 16 Pro 上验证通过，悬浮球唤起可正常读取剪贴板
- [x] 0.4 创建最简 Cloudflare Worker，实现 POST /clip 写入 KV 和 GET /clip 读取 KV，用 curl 验证读写和 Token 校验
- [x] 0.5 初始化 Tauri v2 项目，实现 Menu Bar App 骨架，验证 NSPasteboard 读写和全局快捷键注册

## 1. Cloudflare Worker 云端中转

- [x] 1.1 初始化 Worker 项目（wrangler init），配置 wrangler.toml，绑定 KV namespace
- [x] 1.2 实现 Token 认证中间件（从 wrangler secret 读取 GHOST_TOKEN，校验 Authorization Header）
- [x] 1.3 实现 POST /clip 接口（写入 KV clip:latest，Hash 比对去重避免重复写入）
- [x] 1.4 实现 GET /clip?last_hash=xxx 接口（比对 Hash 返回 200 或 304）
- [x] 1.5 实现 PUT /register 接口（写入 KV online:{device_id}，TTL 15 分钟）
- [x] 1.6 实现 GET /peers 接口（list prefix online: 返回在线设备列表）
- [ ] 1.7 部署到 ghostclip.xkcoding.com，端到端测试所有接口

## 2. Mac 端 Tauri 应用 - 核心功能

- [x] 2.1 搭建 Tauri v2 项目结构，配置为 Menu Bar App（无 Dock 图标）
- [x] 2.2 实现 Rust 后端 NSPasteboard 读写模块（读取 public.utf8-plain-text、写入、监听 changeCount）
- [x] 2.3 实现 MD5 Hash 池（LRU，3 秒 TTL）用于去重
- [x] 2.4 实现全局快捷键注册（默认 Cmd+Shift+C），触发时读取剪贴板并发送
- [x] 2.5 实现快捷键自定义功能（设置界面录入新组合，持久化存储）
- [x] 2.6 实现 Menu Bar 图标状态显示（绿色/黄色/红色）和 Tooltip

## 3. Mac 端 Tauri 应用 - 网络与同步

- [x] 3.1 实现 mDNS/Bonjour 服务注册（服务类型 _ghostclip._tcp）
- [x] 3.2 实现局域网 WebSocket Server，处理 Android 客户端连接和消息收发
- [x] 3.3 实现云端 HTTP 客户端（POST /clip、GET /clip、PUT /register、GET /peers）
- [x] 3.4 实现设备在线感知状态机（IDLE 模式 30s 检查 /peers，POLL 模式 3s 轮询 /clip + 10min 心跳）
- [x] 3.5 实现网络切换策略（局域网优先 → 云端回退 → 局域网恢复时切回）
- [x] 3.6 实现设置界面前端（云端地址、Token、快捷键自定义、通知开关）
- [x] 3.7 实现系统通知功能（可开关，接收数据时显示 macOS 横幅通知）

## 4. Android 端原生应用 - 核心功能

- [x] 4.1 创建正式 Android 项目（Kotlin），配置 minSdk（API 29 / Android 10），基于 spike 代码重构
- [x] 4.2 实现主界面（设置、连接状态、最近同步记录）
- [x] 4.3 实现 QuickSyncActivity（透明 Activity，悬浮球/快捷方式触发 → 前台读取剪贴板 → 投递 → finish）
- [x] 4.4 实现启动方式区分（普通启动 → 主界面；Intent Extra auto_sync=true → QuickSyncActivity）
- [x] 4.5 实现 MD5 Hash 池（LRU，3 秒 TTL）用于去重
- [x] 4.6 实现 ClipboardManager 写入（接收远端数据时静默写入系统剪贴板）
- [x] 4.7 实现 Foreground Service + 常驻通知（用于接收端推送场景）

## 5. Android 端原生应用 - 网络与同步

- [x] 5.1 实现 mDNS/NSD 服务发现（搜索 _ghostclip._tcp 类型服务）
- [x] 5.2 实现局域网 WebSocket Client，连接 Mac 端 Server，处理消息收发
- [x] 5.3 实现云端 HTTP 客户端（POST /clip、GET /clip、PUT /register、GET /peers）
- [x] 5.4 实现设备在线感知状态机（同 Mac 端逻辑：IDLE/POLL 模式切换）
- [x] 5.5 实现网络切换策略（局域网优先 → 云端回退 → WiFi 变化时重新 mDNS 发现）
- [x] 5.6 实现 WebSocket 断线重连（3 秒后重试，失败则重新 mDNS 发现）
- [x] 5.7 实现设置界面（云端地址、Token、启用/禁用云端同步）
- [x] 5.8 实现连接状态展示（Foreground Notification 内容随状态实时更新）

## 6. 体验打磨与适配

- [x] 6.1 Android 端后台保活优化（引导关闭电池优化、省电白名单引导页面）
- [ ] 6.3 Android 端 HyperOS/MIUI 专项适配与保活测试
- [x] 6.4 Mac 端错误处理（网络断开重连、Token 校验失败提示、配置异常提示）
- [ ] 6.5 端到端联调：同一 WiFi 局域网双向同步验证
- [ ] 6.6 端到端联调：不同网络云端同步验证
- [x] 6.7 编写 README（项目介绍、Cloudflare Worker 部署指南、Android APK 安装指南、Mac 安装指南）

## 7. 后续优化（Post-MVP）

- [ ] 7.1 局域网 QR 码配对机制（Mac 显示 QR 码，Android 扫描配对，防止同 WiFi 下误连其他设备）
