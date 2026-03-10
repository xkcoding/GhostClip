## 1. Mac 端基础设施

- [ ] 1.1 实现 IOKit 硬件 MAC 地址获取，sha256 取前 12 位生成 mac_hash
- [ ] 1.2 实现 ephemeral token 生成模块（32 字节 crypto random，hex 编码）
- [ ] 1.3 实现配对状态管理（PairingState: WAITING_PAIR / PAIRED，存储当前 token、已配对 device_id）
- [ ] 1.4 改造 mdns.rs：实例名改为 `gc-{mac_hash}`，TXT 记录增加 `mac_hash` 字段

## 2. Mac 端 WebSocket 鉴权

- [ ] 2.1 改造 ws_server.rs：在 HTTP Upgrade 阶段解析 URL query 中的 token 参数
- [ ] 2.2 实现 token 验证逻辑：匹配当前有效 token，不匹配返回 HTTP 401 拒绝升级
- [ ] 2.3 实现 1:1 踢人逻辑：新设备验证通过时，向旧设备发送 `kicked` 消息后关闭旧连接
- [ ] 2.4 实现配对成功后发送 `pair_ok` 消息
- [ ] 2.5 扩展消息协议：支持 `clip`/`pair_ok`/`kicked`/`unpair` 四种消息类型，无 type 字段的消息兼容为 `clip`

## 3. Mac 端 QR 码与 UI

- [ ] 3.1 添加 `qrcode` crate 依赖，实现 QR 码 SVG 生成（payload: `ghostclip://pair?mac_hash=...&token=...&device=...`）
- [ ] 3.2 实现 Tauri command：`generate_qr_code` 返回 QR SVG 数据、`get_pairing_state` 返回当前配对状态、`unpair` 解除配对
- [ ] 3.3 前端实现 QR 码弹窗组件（独立窗口或 modal）
- [ ] 3.4 Dropdown 增加"配对"/"解除配对"按钮（根据配对状态切换）
- [ ] 3.5 设置页增加"配对"/"解除配对"按钮
- [ ] 3.6 监听配对成功事件，自动关闭 QR 码弹窗
- [ ] 3.7 Dropdown 最近同步记录点击复制：点击列表项 → 复制内容到剪贴板 → hover 态背景色变化
- [ ] 3.8 收到剪贴板内容时弹出 macOS 系统通知：显示来源设备 + 内容预览

## 4. Mac 端 Token 刷新与网络变更

- [ ] 4.1 实现 WiFi 网络变更检测（监听 macOS 网络变化事件）
- [ ] 4.2 网络变更时：生成新 token → 踢掉已配对设备 → 更新 QR 内容 → 状态回到 WAITING_PAIR
- [ ] 4.3 用户解除配对时：发送 `unpair` 消息 → 断开连接 → 生成新 token → 状态回到 WAITING_PAIR

## 5. Android 端扫码

- [ ] 5.1 添加 CameraX + ML Kit Barcode Scanning 依赖
- [ ] 5.2 实现 ScanActivity：CameraX 预览 + ML Kit 实时 QR 识别
- [ ] 5.3 实现 `ghostclip://pair` URI 解析：提取 mac_hash、token、device 参数
- [ ] 5.4 非 GhostClip QR 码提示"请扫描 GhostClip 配对二维码"
- [ ] 5.5 相机权限请求与拒绝处理

## 6. Android 端配对状态与连接

- [ ] 6.1 实现 PairingState 管理（UNPAIRED / CONNECTING / CONNECTED / RECONNECTING），token 和 mac_hash 仅内存存储
- [ ] 6.2 改造 NsdDiscovery.kt：增加 mac_hash 过滤逻辑，仅回调匹配配对 mac_hash 的服务
- [ ] 6.3 改造 LanClient.kt：连接 URL 改为 `ws://{host}:{port}?token={token}`
- [ ] 6.4 实现 mDNS 发现超时处理：5 秒未匹配则提示"请确保两台设备在同一 WiFi"
- [ ] 6.5 改造 NetworkCoordinator.kt：UNPAIRED 状态不启动 mDNS 发现和云端轮询；扫码后才启动发现
- [ ] 6.6 实现同 WiFi 自动重连（RECONNECTING 状态）：保留 token，重新 mDNS 发现后用同一 token 重连
- [ ] 6.7 实现 WiFi 网络切换检测：SSID/BSSID 变化时清除 token，回到 UNPAIRED

## 7. Android 端消息处理与 UI

- [ ] 7.1 处理 `pair_ok` 消息：更新状态为 CONNECTED，记录 Mac 设备名称
- [ ] 7.2 处理 `kicked` 消息：清除 token，回到 UNPAIRED，Toast 提示"已被新设备替代"
- [ ] 7.3 处理 `unpair` 消息：清除 token，回到 UNPAIRED
- [ ] 7.4 主界面增加"扫码配对"按钮（UNPAIRED 时显示）和"解除配对"按钮（CONNECTED 时显示）
- [ ] 7.5 连接状态显示适配：展示 Mac 设备名称、配对状态
- [ ] 7.6 最近同步记录点击复制：点击列表项 → 复制内容到剪贴板 → Toast 提示"已复制"
- [ ] 7.7 前台服务通知适配配对状态：已配对显示设备名+[暂停同步][解除配对]，未配对显示"等待扫码"+[扫码配对]
- [ ] 7.8 收到剪贴板内容时弹出临时通知：预览内容 + [复制] 按钮

## 8. Cloud 通道处理

- [ ] 8.1 Mac 端设置页隐藏 Cloud 配置入口（保留代码，仅隐藏 UI）
- [ ] 8.2 Android 端设置页隐藏 Cloud 配置入口（保留代码，仅隐藏 UI）
- [ ] 8.3 NetworkCoordinator 中跳过 Cloud 初始化逻辑（当 Cloud 未配置时）

## 9. 集成测试与验收

- [ ] 9.1 验证：Mac 启动 → 显示 QR → Android 扫码 → 配对成功 → 剪贴板同步正常
- [ ] 9.2 验证：同 WiFi IP 变化 → 自动重连 → 无需重新扫码
- [ ] 9.3 验证：WiFi 网络切换 → 两端回到未配对状态 → 需重新扫码
- [ ] 9.4 验证：第二台 Android 扫码 → 第一台被踢 → 第二台接管
- [ ] 9.5 验证：未配对设备连接 → 被拒绝（401）
- [ ] 9.6 验证：两端主动解除配对功能正常
- [ ] 9.7 验证：Mac/Android 重启后需重新扫码
