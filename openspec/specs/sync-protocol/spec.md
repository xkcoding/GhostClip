## MODIFIED Requirements

### Requirement: 统一数据格式
所有跨端通信（局域网 WebSocket 和云端 HTTP）SHALL 使用统一的 JSON 数据格式，并新增配对相关消息类型。

```json
// 剪贴板数据消息（保持不变）
{
  "type": "clip",
  "device_id": "string",
  "text": "string",
  "hash": "string (MD5 of text)",
  "timestamp": 1709654400
}

// 配对成功通知（Mac → Android）
{
  "type": "pair_ok",
  "device": "string (Mac 设备名称)"
}

// 设备被踢通知（Mac → 旧 Android）
{
  "type": "kicked",
  "reason": "new_device_paired"
}

// 解除配对通知（任意方向）
{
  "type": "unpair"
}
```

#### Scenario: 剪贴板数据传输
- **WHEN** 通过局域网 WebSocket 发送剪贴板数据
- **THEN** 使用 `type: "clip"` 格式的 JSON 消息体（现有 ClipMessage 增加 type 字段，默认 "clip"；为兼容无 type 字段的消息 SHALL 视为 "clip" 类型）

#### Scenario: 配对成功通知
- **WHEN** Mac 端验证 Android 的 token 通过并接受配对
- **THEN** Mac 端发送 `type: "pair_ok"` 消息，包含 Mac 设备名称

#### Scenario: 设备被踢通知
- **WHEN** 新设备扫码配对成功，Mac 端需踢掉旧设备
- **THEN** Mac 端向旧设备发送 `type: "kicked"` 消息后关闭其连接

#### Scenario: 解除配对通知
- **WHEN** 任意一端主动解除配对
- **THEN** 发起方发送 `type: "unpair"` 消息后关闭 WebSocket 连接

### Requirement: 网络切换策略
客户端 SHALL 基于配对状态管理网络连接。

#### Scenario: 配对后使用局域网
- **WHEN** Android 端扫码配对成功且 WebSocket 连接建立
- **THEN** 使用局域网直连进行剪贴板同步

#### Scenario: 未配对时不主动连接
- **WHEN** Android 端处于 UNPAIRED 状态
- **THEN** 不主动发起任何网络连接（不启动 mDNS 发现，不启动云端轮询），等待用户扫码

#### Scenario: 局域网不可用且无配对
- **WHEN** 局域网不可用且用户未配对
- **THEN** 进入未连接状态，提示用户扫码配对
