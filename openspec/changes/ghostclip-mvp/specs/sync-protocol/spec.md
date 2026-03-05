## ADDED Requirements

### Requirement: 统一数据格式
所有跨端通信（局域网 WebSocket 和云端 HTTP）SHALL 使用统一的 JSON 数据格式。

```json
{
  "device_id": "string",
  "text": "string",
  "hash": "string (MD5 of text)",
  "timestamp": 1709654400
}
```

#### Scenario: 局域网传输
- **WHEN** 通过局域网 WebSocket 发送剪贴板数据
- **THEN** 使用上述 JSON 格式作为 WebSocket 消息体

#### Scenario: 云端传输
- **WHEN** 通过云端 HTTP POST /clip 发送剪贴板数据
- **THEN** 使用上述 JSON 格式作为 HTTP 请求体

### Requirement: Hash 去重防死循环
两端 SHALL 各自维护内存中的 Hash 池（LRU），在发送和接收前进行比对，防止双向同步导致的死循环。

#### Scenario: 发送前去重
- **WHEN** 本端检测到新的剪贴板内容，计算其 MD5 Hash
- **THEN** 如果 Hash 已存在于本地 Hash 池中（3 秒内已处理），丢弃不发送

#### Scenario: 接收后记录
- **WHEN** 本端接收到远端数据并写入剪贴板
- **THEN** 将该数据的 Hash 加入本地 Hash 池，防止写入动作再次触发发送

#### Scenario: Hash 池自动清理
- **WHEN** Hash 池中的条目超过 3 秒有效期
- **THEN** 自动从池中移除过期条目

### Requirement: 非对称同步方向
系统 SHALL 实现非对称的同步方向控制。

#### Scenario: Android 到 Mac 自动同步
- **WHEN** Android 端检测到用户复制了新文本
- **THEN** 自动通过局域网或云端发送至 Mac，无需用户额外操作

#### Scenario: Mac 到 Android 快捷键触发
- **WHEN** Mac 端用户按下配置的全局快捷键
- **THEN** 读取当前 NSPasteboard 内容并发送至 Android
- **WHEN** Mac 端用户仅按 Cmd+C 复制
- **THEN** 不触发跨端同步

### Requirement: 设备在线感知状态机
客户端 SHALL 实现设备在线感知状态机，避免单点在线时的无效云端轮询。

#### Scenario: IDLE 模式（无对端在线）
- **WHEN** GET /peers 返回空列表或仅包含自身
- **THEN** 客户端进入 IDLE 模式：每 30 秒 GET /peers 检查对端是否上线，不发送心跳（自身 online 记录 15 分钟 TTL 后自然过期），不轮询 /clip

#### Scenario: 从 IDLE 切换到 POLL
- **WHEN** IDLE 模式下 GET /peers 发现新的对端设备
- **THEN** 立即 PUT /register 注册自身，切换到 POLL 模式

#### Scenario: POLL 模式（有对端在线）
- **WHEN** 确认对端设备在线
- **THEN** 客户端进入 POLL 模式：每 3 秒 GET /clip?last_hash=xxx 轮询新数据，每 10 分钟 PUT /register 续命心跳

#### Scenario: 从 POLL 切换到 IDLE
- **WHEN** POLL 模式下 GET /peers 发现对端设备的 online 记录已过期
- **THEN** 切换回 IDLE 模式

### Requirement: 网络切换策略
客户端 SHALL 自动在局域网直连和云端中转之间切换。

#### Scenario: 优先局域网
- **WHEN** mDNS 发现对端设备且 WebSocket 连接成功
- **THEN** 使用局域网直连，暂停云端轮询

#### Scenario: 回退云端
- **WHEN** 局域网连接不可用（未发现或连接失败）且云端已配置
- **THEN** 自动切换到云端轮询模式

#### Scenario: 局域网恢复
- **WHEN** 正在使用云端同步时，WiFi 网络变化触发 mDNS 重新发现，找到对端
- **THEN** 建立局域网连接，暂停云端轮询

#### Scenario: 云端未配置
- **WHEN** 局域网不可用且用户未配置云端地址和 Token
- **THEN** 进入未连接状态，等待局域网可用
