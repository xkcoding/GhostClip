## Why

当前 Mac 端和 Android 端的 UI 实现与 `design/ghostclip-ui.pen` 设计稿存在差距。Mac 端前端已较接近设计（约 85%），但 Android 端仅完成基础布局结构（约 40% 视觉完成度）。需要统一对齐设计稿，提升产品视觉一致性和体验质量。

## What Changes

### Android 端
- 对齐主界面（已配对/未配对两种状态）：连接状态卡片样式、最近同步列表样式、调试日志区域
- 对齐设置页：设备配对分组、权限状态分组、关于分组，卡片圆角+分割线样式
- 对齐扫码页：取景框样式、扫描线动画、提示文案位置
- 对齐前台服务通知：已配对/未配对/收到剪贴板三种通知样式
- 字体对齐：标题 Space Grotesk、正文 Inter
- 颜色体系对齐：accent #22C55E、bg #FFFFFF、border #E4E4E7、text #18181B/#71717A/#A1A1AA
- 状态卡片：已连接绿色背景+绿色边框、未配对红色背景+红色边框

### Mac 端
- 对齐 Dropdown 面板：header、设备信息、最近同步（方向箭头+时间戳）、操作区（发送/设置/配对/解除配对/退出）、调试日志
- 对齐 Settings 窗口：快捷键、设备配对（状态+MAC 标识+显示二维码）、通知开关
- 对齐 QR 弹窗：title bar、二维码+logo、说明文案、设备标识
- 对齐通知横幅样式

## Capabilities

### New Capabilities

_(无新增 capability，本次为纯 UI 对齐)_

### Modified Capabilities

_(无 spec 级别的需求变更，仅视觉实现层面调整)_

## Impact

- **Android**: `res/layout/`（3 个 layout XML）、`res/values/`（colors/strings/styles）、`res/drawable/`（状态卡片背景等）、`MainActivity.kt`（同步列表渲染）、`SettingsActivity.kt`（设置页分组）、`GhostClipService.kt`（通知样式）
- **Mac frontend**: `mac/src/app.js`（Dropdown/Settings/QR 渲染逻辑）、`mac/src/styles.css`（样式微调）
- **依赖**: Android 端可能需要引入 Space Grotesk 字体资源；Mac 端已内置
