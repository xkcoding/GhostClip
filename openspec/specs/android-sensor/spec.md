## ADDED Requirements

### Requirement: 无障碍服务剪贴板捕获
Android 端 SHALL 通过 AccessibilityService 监听全局复制事件，并利用透明 Activity 闪现技巧读取剪贴板内容。

#### Scenario: 用户在任意 App 中复制文本
- **WHEN** 用户在任意应用中执行复制操作，AccessibilityService 检测到相关事件（TYPE_VIEW_TEXT_SELECTION_CHANGED 等）
- **THEN** 应用启动透明 Activity（0dp x 0dp），在前台读取 ClipboardManager 内容，读取完毕后立即 finish() 自毁，整个过程用户无感知

#### Scenario: 连续快速复制
- **WHEN** 用户在短时间内（<1s）连续复制多次
- **THEN** 仅处理最后一次复制的内容，中间的复制事件被合并丢弃

### Requirement: 剪贴板写入
Android 端 SHALL 能接收远端数据并静默写入系统剪贴板。

#### Scenario: 接收 Mac 端数据
- **WHEN** 通过 WebSocket 或 HTTP 接收到来自 Mac 端的文本数据
- **THEN** 应用调用 ClipboardManager.setPrimaryClip() 写入系统剪贴板

### Requirement: 后台保活
Android 端 SHALL 通过 Foreground Service + 常驻通知保持后台运行。

#### Scenario: 正常运行
- **WHEN** 应用启动后进入后台
- **THEN** 显示 Foreground Notification，内容展示当前连接状态（已连接 Mac/云端同步中/未连接）

#### Scenario: 系统杀死服务后恢复
- **WHEN** 系统因内存压力杀死应用服务
- **THEN** 服务 MUST 通过 START_STICKY 机制自动重启

### Requirement: 设置界面
Android 端 SHALL 提供设置界面，允许用户配置云端同步参数。

#### Scenario: 配置云端同步
- **WHEN** 用户打开应用设置
- **THEN** 显示以下可配置项：云端地址（域名）、Token、启用/禁用云端同步的开关

#### Scenario: 无障碍服务引导
- **WHEN** 应用检测到无障碍服务未启用
- **THEN** 显示引导页面，指导用户跳转到系统设置开启无障碍服务

#### Scenario: 电池优化引导
- **WHEN** 应用检测到未被排除在电池优化之外
- **THEN** 提示用户关闭电池优化以保证后台稳定运行

### Requirement: 连接状态展示
Android 端 Foreground Notification SHALL 实时反映当前连接状态。

#### Scenario: 局域网已连接
- **WHEN** 通过 mDNS 发现 Mac 并建立 WebSocket 连接
- **THEN** 通知显示 "GhostClip 运行中 · 已连接 Mac (局域网)"

#### Scenario: 云端同步中
- **WHEN** 局域网不可用，云端配置有效且轮询正常
- **THEN** 通知显示 "GhostClip 运行中 · 云端同步中"

#### Scenario: 未连接
- **WHEN** 局域网不可用且云端未配置或连接失败
- **THEN** 通知显示 "GhostClip 运行中 · 未连接到 Mac"
