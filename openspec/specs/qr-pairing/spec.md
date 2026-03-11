## ADDED Requirements

### Requirement: Mac 端 QR 码生成
Mac 端 SHALL 在启动时生成包含配对信息的 QR 码，供 Android 端扫码配对。

#### Scenario: 启动生成配对信息
- **WHEN** Mac 端应用启动
- **THEN** 生成 ephemeral token（32 字节 crypto random，hex 编码为 64 字符），获取硬件 MAC 地址（IOKit）并计算 sha256 取前 12 位作为 mac_hash，组装 QR URI: `ghostclip://pair?mac_hash={mac_hash}&token={token}&device={device_name}`

#### Scenario: 网络变更重新生成
- **WHEN** Mac 端检测到 WiFi 网络变更
- **THEN** 生成新的 ephemeral token，旧 token 立即失效，已连接的设备被断开，QR 码内容更新

#### Scenario: 用户解除配对重新生成
- **WHEN** 用户在 Mac 端点击"解除配对"
- **THEN** 断开当前已配对设备的 WebSocket 连接，生成新的 ephemeral token，QR 码内容更新

### Requirement: Mac 端 QR 码展示
Mac 端 SHALL 在 UI 中提供配对入口，展示 QR 码。

#### Scenario: Dropdown 配对按钮
- **WHEN** 用户点击 Menu Bar Dropdown 中的"配对"按钮
- **THEN** 弹出独立窗口展示当前有效的 QR 码

#### Scenario: 设置页配对按钮
- **WHEN** 用户在设置页点击"配对"按钮
- **THEN** 弹出独立窗口展示当前有效的 QR 码

#### Scenario: 配对成功自动关闭
- **WHEN** Android 端扫码并成功配对
- **THEN** QR 码窗口自动关闭

### Requirement: Android 端内置扫码
Android 端 SHALL 提供内置扫码页面，使用 CameraX 预览 + ML Kit Barcode Scanning 识别 QR 码。

#### Scenario: 打开扫码页面
- **WHEN** 用户在 Android 端点击"扫码配对"按钮
- **THEN** 打开扫码页面，启动相机预览，开始实时识别 QR 码

#### Scenario: 识别成功
- **WHEN** 扫码页面识别到 `ghostclip://pair?` 格式的 QR 码
- **THEN** 解析 mac_hash 和 token，存储到内存（不持久化），关闭扫码页面，开始 mDNS 发现流程

#### Scenario: 识别非 GhostClip QR 码
- **WHEN** 扫码页面识别到非 `ghostclip://` scheme 的 QR 码
- **THEN** 提示用户"请扫描 GhostClip 配对二维码"

#### Scenario: 相机权限
- **WHEN** App 未获得相机权限时用户点击"扫码配对"
- **THEN** 请求相机权限，用户授权后打开扫码页面；用户拒绝则提示需要相机权限

### Requirement: Ephemeral Token 生命周期
系统 SHALL 管理 ephemeral token 的生命周期，确保安全性。

#### Scenario: Token 仅内存存储
- **WHEN** Mac 端生成 ephemeral token
- **THEN** token 仅保存在内存中，不持久化到磁盘，App 退出后自动失效

#### Scenario: Token 唯一有效
- **WHEN** Mac 端生成新的 ephemeral token
- **THEN** 旧 token 立即失效，使用旧 token 的连接请求 SHALL 被拒绝

#### Scenario: Android 端 Token 不持久化
- **WHEN** Android 端从 QR 码获取 token
- **THEN** token 仅保存在内存中，App 退出后丢失，下次启动需重新扫码

### Requirement: 1:1 配对模型
系统 SHALL 实现 1:1 独占配对，同一时刻一台 Mac 只能与一台 Android 配对。

#### Scenario: 新设备扫码踢旧设备
- **WHEN** 已有一台 Android 设备配对成功，第二台 Android 设备扫码连接并通过 token 验证
- **THEN** Mac 端向第一台设备发送 `{"type":"kicked","reason":"new_device_paired"}` 消息后关闭其 WebSocket 连接，接受第二台设备为新的配对设备

#### Scenario: 被踢设备收到通知
- **WHEN** Android 端收到 `type: kicked` 消息
- **THEN** 关闭 WebSocket 连接，清除内存中的 token，回到未配对状态，向用户提示"已被新设备替代"

### Requirement: 主动解除配对
两端 SHALL 提供主动解除配对的功能。

#### Scenario: Mac 端解除配对
- **WHEN** 用户在 Mac 端点击"解除配对"按钮
- **THEN** 向已配对设备发送 `{"type":"unpair"}` 消息，关闭 WebSocket 连接，生成新 token，状态回到 WAITING_PAIR

#### Scenario: Android 端解除配对
- **WHEN** 用户在 Android 端点击"解除配对"按钮
- **THEN** 关闭 WebSocket 连接，清除内存中的 token 和 mac_hash，状态回到 UNPAIRED

### Requirement: 配对状态展示
两端 SHALL 展示当前配对状态。

#### Scenario: Mac 端等待配对
- **WHEN** Mac 端处于 WAITING_PAIR 状态
- **THEN** Dropdown 显示"未配对"状态，显示"配对"按钮

#### Scenario: Mac 端已配对
- **WHEN** Mac 端处于 PAIRED 状态
- **THEN** Dropdown 显示"已配对"状态及对端设备名称，显示"解除配对"按钮

#### Scenario: Android 端未配对
- **WHEN** Android 端处于 UNPAIRED 状态
- **THEN** 显示"扫码配对"按钮，连接状态显示"未连接"

#### Scenario: Android 端已连接
- **WHEN** Android 端处于 CONNECTED 状态
- **THEN** 显示 Mac 设备名称，连接状态显示"已连接"，显示"解除配对"按钮
