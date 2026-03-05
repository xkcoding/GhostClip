## 0. Phase 0: 技术 Spike 验证

- [ ] 0.1 创建最小 Android 项目，注册 AccessibilityService，验证能否检测到其他 App 的复制事件（Android 16 + HyperOS 3.0）
- [ ] 0.2 实现透明 Activity（0dp x 0dp）闪现读取 ClipboardManager，验证能否在后台成功获取剪贴板文本
- [ ] 0.3 如果 0.1/0.2 失败，验证 Plan B（NotificationListenerService 拦截短信验证码）或 Plan C（轻量 IME）的可行性
- [ ] 0.4 创建最简 Cloudflare Worker，实现 POST /clip 写入 KV 和 GET /clip 读取 KV，用 curl 验证读写和 Token 校验
- [ ] 0.5 初始化 Tauri v2 项目，实现 Menu Bar App 骨架，验证 NSPasteboard 读写和全局快捷键注册

## 1. Cloudflare Worker 云端中转

- [ ] 1.1 初始化 Worker 项目（wrangler init），配置 wrangler.toml，绑定 KV namespace
- [ ] 1.2 实现 Token 认证中间件（从 wrangler secret 读取 GHOST_TOKEN，校验 Authorization Header）
- [ ] 1.3 实现 POST /clip 接口（写入 KV clip:latest，Hash 比对去重避免重复写入）
- [ ] 1.4 实现 GET /clip?last_hash=xxx 接口（比对 Hash 返回 200 或 304）
- [ ] 1.5 实现 PUT /register 接口（写入 KV online:{device_id}，TTL 15 分钟）
- [ ] 1.6 实现 GET /peers 接口（list prefix online: 返回在线设备列表）
- [ ] 1.7 部署到 ghostclip.xkcoding.com，端到端测试所有接口

## 2. Mac 端 Tauri 应用 - 核心功能

- [ ] 2.1 搭建 Tauri v2 项目结构，配置为 Menu Bar App（无 Dock 图标）
- [ ] 2.2 实现 Rust 后端 NSPasteboard 读写模块（读取 public.utf8-plain-text、写入、监听 changeCount）
- [ ] 2.3 实现 MD5 Hash 池（LRU，3 秒 TTL）用于去重
- [ ] 2.4 实现全局快捷键注册（默认 Cmd+Shift+C），触发时读取剪贴板并发送
- [ ] 2.5 实现快捷键自定义功能（设置界面录入新组合，持久化存储）
- [ ] 2.6 实现 Menu Bar 图标状态显示（绿色/黄色/红色）和 Tooltip

## 3. Mac 端 Tauri 应用 - 网络与同步

- [ ] 3.1 实现 mDNS/Bonjour 服务注册（服务类型 _ghostclip._tcp）
- [ ] 3.2 实现局域网 WebSocket Server，处理 Android 客户端连接和消息收发
- [ ] 3.3 实现云端 HTTP 客户端（POST /clip、GET /clip、PUT /register、GET /peers）
- [ ] 3.4 实现设备在线感知状态机（IDLE 模式 30s 检查 /peers，POLL 模式 3s 轮询 /clip + 10min 心跳）
- [ ] 3.5 实现网络切换策略（局域网优先 → 云端回退 → 局域网恢复时切回）
- [ ] 3.6 实现设置界面前端（云端地址、Token、快捷键自定义、通知开关）
- [ ] 3.7 实现系统通知功能（可开关，接收数据时显示 macOS 横幅通知）

## 4. Android 端原生应用 - 核心功能

- [ ] 4.1 创建 Android 项目（Kotlin），配置 minSdk（API 29 / Android 10）
- [ ] 4.2 实现 AccessibilityService 注册与配置（监听 TYPE_VIEW_TEXT_SELECTION_CHANGED 等事件）
- [ ] 4.3 实现透明 Activity 闪现读取剪贴板（0dp x 0dp Activity，前台读取 ClipboardManager，立即 finish）
- [ ] 4.4 实现连续复制事件的防抖合并（1s 内多次复制只处理最后一次）
- [ ] 4.5 实现 MD5 Hash 池（LRU，3 秒 TTL）用于去重
- [ ] 4.6 实现 ClipboardManager 写入（接收远端数据时静默写入系统剪贴板）
- [ ] 4.7 实现 Foreground Service + 常驻通知（START_STICKY 自动重启）

## 5. Android 端原生应用 - 网络与同步

- [ ] 5.1 实现 mDNS/NSD 服务发现（搜索 _ghostclip._tcp 类型服务）
- [ ] 5.2 实现局域网 WebSocket Client，连接 Mac 端 Server，处理消息收发
- [ ] 5.3 实现云端 HTTP 客户端（POST /clip、GET /clip、PUT /register、GET /peers）
- [ ] 5.4 实现设备在线感知状态机（同 Mac 端逻辑：IDLE/POLL 模式切换）
- [ ] 5.5 实现网络切换策略（局域网优先 → 云端回退 → WiFi 变化时重新 mDNS 发现）
- [ ] 5.6 实现 WebSocket 断线重连（3 秒后重试，失败则重新 mDNS 发现）
- [ ] 5.7 实现设置界面（云端地址、Token、启用/禁用云端同步）
- [ ] 5.8 实现连接状态展示（Foreground Notification 内容随状态实时更新）

## 6. 体验打磨与适配

- [ ] 6.1 Android 端后台保活优化（引导关闭电池优化、省电白名单引导页面）
- [ ] 6.2 Android 端无障碍服务未启用时的引导页面
- [ ] 6.3 Android 端 HyperOS/MIUI 专项适配与保活测试
- [ ] 6.4 Mac 端错误处理（网络断开重连、Token 校验失败提示、配置异常提示）
- [ ] 6.5 端到端联调：同一 WiFi 局域网双向同步验证
- [ ] 6.6 端到端联调：不同网络云端同步验证
- [ ] 6.7 编写 README（项目介绍、Cloudflare Worker 部署指南、Android APK 安装指南、Mac 安装指南）
