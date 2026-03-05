# GhostClip

Android 与 Mac 之间的跨端剪贴板同步工具。在一台设备上复制文字，另一台设备上直接粘贴。

## 特性

- **局域网直连**：同一 WiFi 下通过 mDNS 自动发现 + WebSocket 直连，延迟 <10ms
- **云端中转**：不同网络时通过 Cloudflare Worker 中转，延迟 ~3s
- **零成本运行**：完全使用 Cloudflare 免费额度，运行成本 $0
- **智能轮询**：设备在线感知，单点在线时不做无效轮询
- **非对称同步**：Android -> Mac 自动同步，Mac -> Android 通过快捷键触发

## 架构

```
┌─────────────┐         ┌──────────────────┐         ┌─────────────┐
│  Android App│◄──LAN──►│  Mac Tauri App   │         │  Cloudflare │
│  (Kotlin)   │  mDNS + │  (Rust + Web UI) │◄──WAN──►│   Worker    │
│             │WebSocket│                  │  HTTP    │  (KV Store) │
└──────┬──────┘         └──────────────────┘         └──────┬──────┘
       │                                                     │
       └──────────────── WAN (HTTP 轮询) ───────────────────┘
```

| 组件 | 目录 | 技术栈 |
|------|------|--------|
| Cloudflare Worker | `worker/` | TypeScript + Hono + KV |
| Mac 端 | `mac/` | Tauri v2 (Rust 后端 + Web 前端) |
| Android 端 | `android/` | Kotlin + Jetpack Compose |

## 部署指南

### 1. Cloudflare Worker 部署

**前提条件**：已安装 [Node.js](https://nodejs.org/)（>= 18）和 [Wrangler CLI](https://developers.cloudflare.com/workers/wrangler/install-and-update/)。

```bash
# 进入 Worker 目录
cd worker

# 安装依赖
npm install

# 创建 KV 命名空间
wrangler kv namespace create GHOSTCLIP_KV
# 输出会包含 KV namespace ID，例如：
# { binding = "KV", id = "xxxxxxxxxxxxxxxxxxxx" }
```

将输出的 `id` 值填入 `wrangler.toml`：

```toml
[[kv_namespaces]]
binding = "KV"
id = "你的 KV namespace ID"
```

设置认证 Token（客户端连接时需要使用相同的 Token）：

```bash
wrangler secret put GHOST_TOKEN
# 按提示输入你的自定义密钥
```

部署到 Cloudflare：

```bash
npm run deploy
```

（可选）绑定自定义域名：在 Cloudflare Dashboard 中为 Worker 添加自定义域名路由，例如 `ghostclip.yourdomain.com`。

**本地开发**：

```bash
# 创建 .dev.vars 文件写入本地测试 Token
echo "GHOST_TOKEN=your-dev-token" > .dev.vars

# 启动本地开发服务器
npm run dev
```

### 2. Mac 端安装

1. 从 [Releases](../../releases) 页面下载最新的 `.dmg` 文件
2. 打开 DMG，将 GhostClip 拖入应用程序文件夹
3. 首次启动时，在系统设置中允许运行
4. GhostClip 会出现在 Menu Bar（菜单栏），不会显示 Dock 图标
5. 点击 Menu Bar 图标 -> 设置，配置：
   - **云端地址**：你部署的 Worker 域名（如 `ghostclip.yourdomain.com`）
   - **Token**：与 Worker 部署时设置的相同密钥
   - **发送快捷键**：默认 `Cmd+Shift+C`，可自定义

### 3. Android 端安装

1. 从 [Releases](../../releases) 页面下载最新的 `.apk` 文件
2. 在手机上安装 APK（需允许"安装未知来源应用"）
3. 打开 GhostClip，在设置中配置：
   - **云端地址**：你部署的 Worker 域名
   - **Token**：与 Worker 部署时设置的相同密钥
4. 配置小米悬浮球或系统快捷方式，指向 GhostClip 快速同步功能（小米手机：设置 -> 更多设置 -> 悬浮球，添加 GhostClip 快捷方式）

## 使用方法

### Android -> Mac（自动同步）

1. 在 Android 任意应用中复制文字
2. 点击悬浮球/快捷方式打开 GhostClip
3. App 自动读取剪贴板并发送到 Mac
4. Mac 端自动接收并写入剪贴板，直接 `Cmd+V` 粘贴

### Mac -> Android（快捷键触发）

1. 在 Mac 上复制文字（`Cmd+C`）
2. 按下发送快捷键（默认 `Cmd+Shift+C`）
3. Android 端自动接收并写入剪贴板，可直接粘贴

### 连接状态

| 状态 | Mac Menu Bar | Android 通知 |
|------|-------------|-------------|
| 局域网已连接 | 绿色图标 | "已连接 Mac (局域网)" |
| 云端同步中 | 黄色图标 | "云端同步中" |
| 未连接 | 红色图标 | "未连接到 Mac" |

## 网络策略

- **局域网优先**：同一 WiFi 下自动通过 mDNS 发现对端，建立 WebSocket 直连
- **云端回退**：局域网不可用时，自动切换到 Cloudflare Worker 轮询
- **自动恢复**：WiFi 变化时重新尝试局域网发现，优先切回直连

## API 接口

Worker 提供以下 REST API（所有请求需携带 `Authorization: Bearer <token>`）：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/clip` | 写入剪贴板数据（hash 去重） |
| `GET` | `/clip?last_hash=` | 轮询最新数据（304 表示无更新） |
| `PUT` | `/register` | 注册设备在线状态（15 分钟 TTL） |
| `GET` | `/peers` | 查询在线设备列表 |

## 技术说明

- **去重机制**：两端各维护 MD5 Hash 池（LRU，3 秒 TTL），防止同步死循环
- **在线感知**：IDLE 模式每 30s 检查对端；POLL 模式每 3s 轮询数据 + 每 10min 续命心跳
- **仅限文本**：当前版本仅支持纯文本同步，不支持图片或文件
- **安全性**：局域网通信有 WPA 加密，云端通信有 HTTPS/TLS 加密

## License

MIT
