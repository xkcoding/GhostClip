## ADDED Requirements

### Requirement: UI 对齐设计稿
两端 UI SHALL 100% 对齐 `design/ghostclip-ui.pen` 设计稿的视觉规范，包括颜色、字体、间距、圆角和状态样式。

#### Scenario: Android 已配对主界面
- **WHEN** Android 端处于已配对状态
- **THEN** 连接状态卡片 SHALL 使用绿色背景（#F0FDF4）+ 绿色边框（#22C55E, 1.5px）+ 圆角 16px，显示绿色圆点+「已连接」文字、设备名称+连接类型、解除配对按钮（红色浅背景）

#### Scenario: Android 未配对主界面
- **WHEN** Android 端处于未配对状态
- **THEN** 连接状态卡片 SHALL 使用红色背景（#FEF2F2）+ 红色边框（#FECACA, 1.5px），显示灰色圆点+「未连接」文字、绿色「扫码配对」按钮、提示「请使用扫码功能连接 Mac 设备」

#### Scenario: Android 最近同步列表
- **WHEN** 存在同步历史记录
- **THEN** 同步列表 SHALL 为圆角 16px 卡片 + #E4E4E7 边框，每项显示方向图标+内容预览+时间戳，项间有 1px 分割线

#### Scenario: Android 设置页分组
- **WHEN** 用户进入设置页
- **THEN** SHALL 展示三个分组：设备配对（配对状态+MAC 标识+解除配对）、权限状态（电池优化）、关于（版本号+GitHub），每组用大写灰色标签+圆角卡片+内部分割线

#### Scenario: Mac Dropdown 面板
- **WHEN** 用户点击 Mac 端 Menu Bar 图标
- **THEN** Dropdown SHALL 按设计稿布局：header（绿色圆点+标题+状态）→ 设备信息 → 最近同步（方向箭头+内容+时间）→ 操作区（发送/设置/配对/解除配对/退出）→ 调试日志

#### Scenario: Mac Settings 窗口
- **WHEN** 用户打开设置窗口
- **THEN** SHALL 展示：快捷键区、设备配对区（配对状态+MAC 标识+显示二维码按钮）、通知开关+描述文案

#### Scenario: 字体规范
- **WHEN** 渲染两端 UI
- **THEN** 标题类文字 SHALL 使用 Space Grotesk（fontWeight 600），正文和标签 SHALL 使用 Inter
