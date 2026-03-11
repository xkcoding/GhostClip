## ADDED Requirements

### Requirement: Mac 端 mDNS 服务注册
Mac 端 SHALL 通过 mDNS/Bonjour 在局域网注册服务，使 Android 端能够自动发现。

#### Scenario: 注册服务
- **WHEN** Mac 端应用启动
- **THEN** 注册 mDNS 服务，服务类型为 `_ghostclip._tcp`，端口为应用监听的 WebSocket 端口

#### Scenario: 注销服务
- **WHEN** Mac 端应用退出
- **THEN** 注销 mDNS 服务

### Requirement: Android 端 mDNS 服务发现
Android 端 SHALL 通过 mDNS/NSD 在局域网发现 Mac 端注册的 GhostClip 服务。

#### Scenario: 发现 Mac 服务
- **WHEN** Android 端在同一 WiFi 网络下启动或网络切换时
- **THEN** 搜索 `_ghostclip._tcp` 类型的 mDNS 服务，找到后获取 Mac 的 IP 地址和端口

#### Scenario: Mac 不在线
- **WHEN** Android 端搜索 mDNS 服务超过 5 秒未发现结果
- **THEN** 标记局域网不可用，回退到云端同步模式（如已配置）

### Requirement: 局域网 WebSocket 通信
Mac 端 SHALL 启动局域网 WebSocket Server，Android 端作为 Client 连接。

#### Scenario: 建立连接
- **WHEN** Android 通过 mDNS 发现 Mac 的 IP 和端口
- **THEN** Android 建立 WebSocket 连接到 Mac，双方交换 device_id 完成握手

#### Scenario: 数据传输
- **WHEN** 任意一端有新的剪贴板数据需要发送
- **THEN** 通过已建立的 WebSocket 连接发送 JSON 数据包（格式同云端协议）

#### Scenario: 连接断开后重连
- **WHEN** WebSocket 连接异常断开（如 WiFi 切换）
- **THEN** Android 端 MUST 在 3 秒后尝试重连，重连失败则重新进行 mDNS 发现

#### Scenario: 网络切换
- **WHEN** Android 端检测到 WiFi 网络变化
- **THEN** 重新进行 mDNS 发现流程，尝试建立新的局域网连接
