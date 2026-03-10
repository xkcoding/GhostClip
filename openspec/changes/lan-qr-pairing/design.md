## Context

GhostClip 当前 LAN 连接基于 mDNS 自动发现 + WebSocket 直连，无任何鉴权机制。在公司等共享 WiFi 环境中，多人使用时会导致所有人的剪贴板互相同步。需要引入 QR 码扫码配对机制，在保留 mDNS 自动 IP 解析能力的同时，增加设备级鉴权。

当前架构：
- Mac 通过 `dns-sd` CLI 注册 `_ghostclip._tcp` mDNS 服务（TXT: device_id, version）
- Android 通过 NsdManager 发现服务，直连 WebSocket，无鉴权
- WebSocket 消息格式：`{device_id, text, hash, timestamp}`

## Goals / Non-Goals

**Goals:**
- 同 WiFi 下只有经过扫码配对的设备对才能同步剪贴板
- 保留 mDNS 的 IP 自动解析能力（同 WiFi 内 IP 变化无需重扫）
- 通过 MAC hash 区分同网络上的多台 GhostClip Mac 实例
- 1:1 配对模型，新设备扫码踢掉旧设备
- App 重启或 WiFi 网络变更时重新生成 token，需重新扫码

**Non-Goals:**
- Cloud 通道的配对机制（暂不迭代 Cloud 功能）
- 1:N 多设备配对
- 持久化配对信息（token 不跨重启保留）
- 加密 WebSocket 传输内容（局域网信任模型）

## Decisions

### D1: mDNS 保留 + QR 码仅携带配对凭证（方案 C）

**选择**: 保留 mDNS 负责 IP/端口动态解析，QR 码只携带 `mac_hash` + `token`，不携带 IP/端口。

**替代方案**:
- 方案 A（去掉 mDNS，QR 含 IP+port）：同 WiFi 内 IP 变化需重扫，体验差
- 方案 B（mDNS + QR 含 IP+port）：IP 信息冗余，mDNS 已能解析

**理由**: 职责分离 — mDNS 解决"在哪"，QR 解决"有没有资格连"。IP 变化时 mDNS 自动更新，Android 用同一 token 重连，用户无感。

### D2: 硬件 MAC 地址 (IOKit) 取 hash 作为设备指纹

**选择**: 通过 macOS IOKit 获取硬件 MAC 地址，sha256 取前 12 位作为 `mac_hash`。

**替代方案**:
- 使用现有 `device_id`（machine-uid）：已经唯一，但无法区分"同一台 Mac 的不同网络接口"
- 使用 WiFi 接口 MAC：macOS Private WiFi Address 可能随机化，不稳定

**理由**: 硬件 MAC 通过 IOKit 读取稳定可靠，不受 Private WiFi Address 影响。hash 后避免直接暴露原始 MAC。mDNS 实例名用 `gc-{mac_hash}` 格式，既能区分多台 Mac，又不泄露 "GhostClip" 以外的信息。

### D3: Ephemeral Token 生命周期

**选择**: token 在以下时机重新生成（旧 token 立即失效）：
1. Mac App 启动
2. WiFi 网络变更（networkchange 事件）
3. 用户手动解除配对

**替代方案**:
- 持久化 token（跨重启保留）：更便利但安全性降低，不符合用户需求
- 定时轮换：增加复杂度，ephemeral 已够安全

**理由**: 用户明确要求每次重启/网络变更需重新扫码。Ephemeral 模型最安全 — token 仅存活于单次运行会话。

### D4: WebSocket URL Query 传递 Token

**选择**: `ws://host:port?token=xxx`，在 HTTP Upgrade 阶段验证 token。

**替代方案**:
- 连接后首条消息鉴权：连接已建立但未鉴权的窗口期存在安全风险
- TLS 客户端证书：局域网场景过重

**理由**: 在 TCP 握手的 HTTP Upgrade 阶段就验证，未授权连接直接拒绝（返回 401），不进入 WebSocket 帧交互。tokio-tungstenite 支持在 accept 前检查 HTTP request。

### D5: Android 内置扫码（CameraX + ML Kit）

**选择**: App 内置扫码页面，使用 CameraX 预览 + ML Kit Barcode Scanning。

**替代方案**:
- 系统相机 Intent：需要注册 URL scheme，跨 App 体验碎片化
- ZXing 库：较老，ML Kit 是 Google 官方推荐

**理由**: 内置扫码体验最完整，不依赖外部 App。ML Kit 免费、离线、识别速度快。CameraX 简化相机生命周期管理。

### D6: QR 码 URI Scheme

**格式**: `ghostclip://pair?mac_hash={mac_hash}&token={token}&device={device_name}`

- `mac_hash`: 12 字符 hex，用于 mDNS 过滤匹配
- `token`: 64 字符 hex（32 字节 crypto random），用于 WebSocket 鉴权
- `device`: URL-encoded 设备名称，仅展示用

### D7: Mac 端 QR 码生成与展示

**选择**: Rust 端用 `qrcode` crate 生成 QR 码 SVG/PNG，通过 Tauri command 传递给前端展示。

**展示入口**:
- Menu Bar Dropdown 中的"配对"按钮
- 设置页中的"配对"按钮
- 点击后弹出独立窗口展示 QR 码，配对成功后自动关闭

### D8: 配对状态机

**Mac 端状态**:
```
WAITING_PAIR ──(Android 连接 + token 验证通过)──► PAIRED
PAIRED ──(连接断开 / 网络变更 / 用户解除 / App 退出)──► WAITING_PAIR
PAIRED ──(新设备扫码)──► 踢旧设备 → PAIRED (新设备)
```

**Android 端状态**:
```
UNPAIRED ──(用户扫码)──► CONNECTING
CONNECTING ──(pair_ok 收到)──► CONNECTED
CONNECTING ──(token 拒绝 / 超时)──► UNPAIRED
CONNECTED ──(断开 + 同 WiFi)──► RECONNECTING
RECONNECTING ──(mDNS 重新发现 + 同 token 重连)──► CONNECTED
CONNECTED ──(被踢 / 网络变更 / 用户解除)──► UNPAIRED
```

**RECONNECTING vs UNPAIRED 的关键区别**:
- RECONNECTING: 同 WiFi 内的临时断开（IP 变化、短暂网络抖动），保留 token，自动通过 mDNS 重新发现并重连
- UNPAIRED: WiFi 网络本身切换，或被踢/主动解除，清除 token，需重新扫码

## Risks / Trade-offs

- **[每次重启需扫码]** → 安全性与便利性的 trade-off。用户明确选择安全优先。未来可根据反馈放宽为"同 WiFi 保持配对"。

- **[mDNS 服务仍可见]** → 他人能发现 `_ghostclip._tcp` 服务存在，但无 token 无法连接。安全靠鉴权不靠隐藏（security by obscurity 不可靠）。可接受。

- **[MAC 地址 hash 可预测]** → 如果攻击者知道目标的硬件 MAC，可以算出 mac_hash。但这只帮助他找到"哪个 mDNS 服务是目标的"，仍然无法获取 ephemeral token。风险可控。

- **[CameraX + ML Kit 包体积]** → ML Kit Barcode 约增加 2-3MB APK 大小。对工具类 App 可接受。

- **[WebSocket 明文传输]** → 局域网场景，WiFi 本身有 WPA 加密。如需更高安全性，未来可升级为 WSS（自签证书）。当前 MVP 阶段可接受。
