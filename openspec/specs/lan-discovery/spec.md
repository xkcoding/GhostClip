## MODIFIED Requirements

### Requirement: Mac 端 mDNS 服务注册
Mac 端 SHALL 通过 mDNS/Bonjour 在局域网注册服务，使用 MAC hash 作为实例标识，使 Android 端能够精确匹配已配对的 Mac。

#### Scenario: 注册服务
- **WHEN** Mac 端应用启动
- **THEN** 注册 mDNS 服务，实例名为 `gc-{mac_hash}`，服务类型为 `_ghostclip._tcp`，端口为应用监听的 WebSocket 端口，TXT 记录包含 `mac_hash={mac_hash}`（mac_hash 为硬件 MAC 地址 sha256 的前 12 位 hex）

#### Scenario: 注销服务
- **WHEN** Mac 端应用退出
- **THEN** 注销 mDNS 服务

### Requirement: Android 端 mDNS 服务发现
Android 端 SHALL 通过 mDNS/NSD 在局域网发现 Mac 端服务，并按 mac_hash 过滤匹配已配对的 Mac。

#### Scenario: 扫码后发现 Mac 服务
- **WHEN** Android 端扫码获取 mac_hash 后启动 mDNS 发现
- **THEN** 搜索 `_ghostclip._tcp` 类型的 mDNS 服务，在发现的服务中匹配 TXT 记录 `mac_hash` 与扫码获取的值一致的服务，获取其 IP 地址和端口

#### Scenario: 忽略非配对 Mac
- **WHEN** Android 端发现 `_ghostclip._tcp` 服务但其 mac_hash 与已配对的不匹配
- **THEN** 忽略该服务，不尝试连接

#### Scenario: 未扫码时不主动发现
- **WHEN** Android 端处于 UNPAIRED 状态（未扫码）
- **THEN** 不启动 mDNS 发现，等待用户扫码

#### Scenario: 配对 Mac 不在线
- **WHEN** Android 端扫码后搜索 mDNS 服务超过 5 秒未发现匹配 mac_hash 的服务
- **THEN** 提示用户"请确保两台设备在同一 WiFi"

### Requirement: 局域网 WebSocket 通信
Mac 端 SHALL 启动局域网 WebSocket Server，Android 端作为 Client 连接，连接时 SHALL 携带 token 进行鉴权。

#### Scenario: 建立连接（带 Token 鉴权）
- **WHEN** Android 通过 mDNS 发现配对 Mac 的 IP 和端口
- **THEN** Android 建立 WebSocket 连接到 `ws://{host}:{port}?token={token}`，Mac 端在 HTTP Upgrade 阶段验证 token，验证通过后升级为 WebSocket 连接

#### Scenario: Token 验证失败
- **WHEN** WebSocket 连接请求携带的 token 与 Mac 端当前有效 token 不匹配
- **THEN** Mac 端拒绝连接（返回 HTTP 401），不升级为 WebSocket

#### Scenario: 未携带 Token
- **WHEN** WebSocket 连接请求未携带 token 参数
- **THEN** Mac 端拒绝连接（返回 HTTP 401）

#### Scenario: 数据传输
- **WHEN** 任意一端有新的剪贴板数据需要发送
- **THEN** 通过已建立的 WebSocket 连接发送 JSON 数据包（格式同云端协议）

#### Scenario: 同 WiFi 内连接断开后自动重连
- **WHEN** 已配对状态下 WebSocket 连接异常断开（如 IP 变化、短暂网络抖动），WiFi 网络未切换
- **THEN** Android 端保留 token 和 mac_hash，重新启动 mDNS 发现，匹配到 Mac 后使用相同 token 重连

#### Scenario: WiFi 网络切换
- **WHEN** Android 端检测到 WiFi 网络本身发生切换（连接到不同 SSID 或 BSSID）
- **THEN** 清除 token 和 mac_hash，回到 UNPAIRED 状态，需重新扫码配对
