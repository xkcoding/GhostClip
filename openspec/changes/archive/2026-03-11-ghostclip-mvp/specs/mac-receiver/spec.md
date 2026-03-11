## ADDED Requirements

### Requirement: Menu Bar 常驻应用
Mac 端 SHALL 作为 Menu Bar App 常驻运行，不显示 Dock 图标。

#### Scenario: 应用启动
- **WHEN** 用户启动 GhostClip Mac 端
- **THEN** 应用仅在 Menu Bar 显示状态图标，不出现在 Dock 栏

#### Scenario: 状态图标
- **WHEN** 应用运行中
- **THEN** Menu Bar 图标通过颜色反映连接状态：绿色（局域网已连接）、黄色（云端同步中）、红色（未连接）

#### Scenario: 图标 Tooltip
- **WHEN** 用户将鼠标悬停在 Menu Bar 图标上
- **THEN** 显示 Tooltip 描述当前状态，如 "GhostClip · 已连接 Android (局域网)"

### Requirement: 自动接收并写入剪贴板
Mac 端 SHALL 自动接收来自 Android 端的文本数据，并静默写入系统剪贴板。

#### Scenario: 接收 Android 数据
- **WHEN** 通过 WebSocket（局域网）或 HTTP 轮询（云端）接收到 Android 端的文本
- **THEN** 应用将文本写入 NSPasteboard，用户可直接 Cmd+V 粘贴

### Requirement: 自定义快捷键发送
Mac 端 SHALL 支持通过全局快捷键将当前剪贴板内容发送至 Android 端。快捷键 MUST 支持用户自定义。

#### Scenario: 默认快捷键发送
- **WHEN** 用户按下默认快捷键 Cmd+Shift+C
- **THEN** 应用读取 NSPasteboard 当前内容，通过 WebSocket 或 HTTP 发送至 Android 端

#### Scenario: 自定义快捷键
- **WHEN** 用户在设置界面修改快捷键绑定
- **THEN** 新的快捷键组合立即生效，替代旧的快捷键

#### Scenario: 普通复制不触发同步
- **WHEN** 用户按下 Cmd+C 执行普通复制
- **THEN** 不触发跨端同步，文本仅留在 Mac 本地剪贴板

### Requirement: 系统通知（可配置）
Mac 端 SHALL 提供可开关的系统通知功能，用于提示接收到的跨端数据。

#### Scenario: 通知开启时接收数据
- **WHEN** 通知开关开启且接收到 Android 端数据
- **THEN** 通过 macOS UserNotification 显示横幅 "来自 Android 的剪贴板已同步"

#### Scenario: 通知关闭时接收数据
- **WHEN** 通知开关关闭且接收到 Android 端数据
- **THEN** 静默写入剪贴板，不显示任何通知

### Requirement: 设置界面
Mac 端 SHALL 通过 Menu Bar 下拉菜单提供设置入口。

#### Scenario: 打开设置
- **WHEN** 用户点击 Menu Bar 图标并选择"设置"
- **THEN** 显示设置窗口，包含以下配置项：发送快捷键绑定、云端地址（域名）、Token、启用/禁用云端同步、接收通知开关

### Requirement: NSPasteboard 监听
Mac 端 SHALL 持续监听系统剪贴板变化，用于 Hash 去重。

#### Scenario: 检测剪贴板变化
- **WHEN** 系统剪贴板的 changeCount 增加
- **THEN** 应用读取 public.utf8-plain-text 类型内容，计算 MD5 Hash 并更新本地 Hash 池
