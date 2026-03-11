## Why

当前 LAN 连接零认证 — 同一 WiFi 下任何运行 GhostClip 的设备都能自动发现并连接，导致在公司等共享网络环境中，多人使用时所有人的剪贴板会互相同步。需要引入设备配对机制，确保只有经过扫码授权的设备对才能同步。

## What Changes

- Mac 端 mDNS 注册时加入硬件 MAC 地址 hash 作为实例标识，区分同网络多台 Mac
- Mac 端每次启动/网络变更时生成 ephemeral token，通过 QR 码展示配对信息（mac_hash + token）
- Android 端新增内置扫码页面，扫码后按 mac_hash 过滤 mDNS 服务、用 token 鉴权连接
- WebSocket 连接升级时通过 URL query 携带 token，Mac 端验证后才允许通信
- 1:1 配对模型：新设备扫码时踢掉已配对设备
- 每次 App 重启或网络变更需重新扫码配对
- 暂停 Cloud 通道迭代，聚焦 LAN 体验
- **BREAKING**: LAN WebSocket 连接不再无条件接受，需 token 鉴权

## Capabilities

### New Capabilities
- `qr-pairing`: QR 码设备配对机制，包含 Mac 端 QR 生成/展示、Android 端扫码解析、ephemeral token 生命周期管理、1:1 配对踢人逻辑

### Modified Capabilities
- `lan-discovery`: mDNS 注册补充 mac_hash TXT 记录；Android 端按 mac_hash 过滤发现结果而非连接所有服务
- `sync-protocol`: WebSocket 握手增加 token 鉴权（`?token=xxx`）；新增 `pair_ok`/`kicked`/`unpair` 消息类型

## Impact

- **Mac Rust 后端** (`mac/src-tauri/src/`): `mdns.rs` 增加 mac_hash TXT 记录；`ws_server.rs` 增加 token 验证逻辑；新增 QR 码生成和配对状态管理模块
- **Mac Web 前端** (`mac/src/`): Dropdown 和设置页增加配对按钮及 QR 码弹窗
- **Android** (`android/`): 新增扫码页面（CameraX + ML Kit）；`NsdDiscovery.kt` 增加 mac_hash 过滤；`LanClient.kt` 连接 URL 携带 token；`NetworkCoordinator.kt` 适配配对状态机
- **依赖变更**: Android 新增 CameraX + ML Kit Barcode Scanning 依赖；Mac 新增 QR 码生成 crate
- **Cloud 通道**: 暂不改动，保留代码但不迭代功能
