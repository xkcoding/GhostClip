## ADDED Requirements

### Requirement: Token 认证
Worker SHALL 对所有 API 请求进行 Token 认证。Token 通过 `wrangler secret put GHOST_TOKEN` 在部署时配置。客户端请求 MUST 在 HTTP Header 中携带 `Authorization: Bearer <token>`。

#### Scenario: 合法请求
- **WHEN** 客户端携带正确的 Authorization Header 发起请求
- **THEN** Worker 正常处理请求并返回对应响应

#### Scenario: 缺少 Token
- **WHEN** 客户端未携带 Authorization Header
- **THEN** Worker 返回 HTTP 401 Unauthorized

#### Scenario: 错误 Token
- **WHEN** 客户端携带的 Token 与服务端配置不匹配
- **THEN** Worker 返回 HTTP 401 Unauthorized

### Requirement: 剪贴板数据写入
Worker SHALL 提供 `POST /clip` 接口，接收客户端发送的剪贴板数据并存入 KV。

请求体格式：
```json
{
  "device_id": "string",
  "text": "string",
  "hash": "string (MD5)",
  "timestamp": 1709654400
}
```

#### Scenario: 写入新数据
- **WHEN** 客户端 POST /clip 携带合法数据
- **THEN** Worker 将数据写入 KV key `clip:latest` 并返回 HTTP 200

#### Scenario: 重复数据
- **WHEN** 客户端 POST /clip 携带的 hash 与 KV 中现有数据的 hash 相同
- **THEN** Worker 返回 HTTP 200 但不执行 KV 写入（节省写入配额）

### Requirement: 剪贴板数据读取
Worker SHALL 提供 `GET /clip?last_hash=<hash>` 接口，客户端轮询获取最新剪贴板数据。

#### Scenario: 有新数据
- **WHEN** 客户端 GET /clip 携带的 last_hash 与 KV 中数据的 hash 不同
- **THEN** Worker 返回 HTTP 200 及完整的剪贴板数据 JSON

#### Scenario: 无新数据
- **WHEN** 客户端 GET /clip 携带的 last_hash 与 KV 中数据的 hash 相同
- **THEN** Worker 返回 HTTP 304 Not Modified（空响应体）

#### Scenario: 无任何数据
- **WHEN** KV 中不存在 `clip:latest` key
- **THEN** Worker 返回 HTTP 304 Not Modified

### Requirement: 设备在线注册
Worker SHALL 提供 `PUT /register` 接口，设备调用此接口注册自身在线状态。

请求体格式：
```json
{
  "device_id": "string",
  "device_type": "android | mac"
}
```

#### Scenario: 设备注册
- **WHEN** 客户端 PUT /register 携带 device_id 和 device_type
- **THEN** Worker 写入 KV key `online:<device_id>`，TTL 设为 15 分钟，返回 HTTP 200

#### Scenario: 设备续命
- **WHEN** 已注册设备再次 PUT /register
- **THEN** Worker 更新 KV key `online:<device_id>` 的 TTL 为 15 分钟

### Requirement: 在线设备查询
Worker SHALL 提供 `GET /peers` 接口，返回当前在线的设备列表。

#### Scenario: 存在在线设备
- **WHEN** 客户端 GET /peers
- **THEN** Worker 返回所有未过期的 `online:*` KV 条目，格式为 `{"peers": [{"device_id": "...", "device_type": "..."}]}`

#### Scenario: 无在线设备
- **WHEN** 所有 `online:*` KV 条目已过期
- **THEN** Worker 返回 `{"peers": []}`

### Requirement: 部署配置
Worker SHALL 绑定到用户自定义域名（如 ghostclip.xkcoding.com），并通过 wrangler.toml 配置 KV namespace 绑定。

#### Scenario: 域名访问
- **WHEN** 用户通过自定义域名访问 Worker
- **THEN** Worker 正常响应所有 API 请求，TLS 由 Cloudflare 自动提供
