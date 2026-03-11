## Context

GhostClip 的核心功能（QR 扫码配对 + LAN 剪贴板同步）已全部实现并合入 main。当前 UI 状态：
- **Mac 前端**（app.js + styles.css）：已较接近设计稿，约 85% 视觉完成度，需要细节微调
- **Android 端**（XML layouts + Kotlin）：基础布局完成，但视觉样式与设计稿差距较大（约 40%），需要全面对齐

设计稿 `design/ghostclip-ui.pen` 包含 13 个界面，覆盖两端全部场景。

## Goals / Non-Goals

**Goals:**
- Android 端 UI 100% 对齐设计稿（颜色、字体、间距、圆角、状态卡片样式）
- Mac 前端 UI 100% 对齐设计稿（Dropdown、Settings、QR Popup、通知横幅）
- 两端视觉风格统一（颜色体系、字体体系、间距节奏）

**Non-Goals:**
- 不修改任何功能逻辑或网络层代码
- 不新增功能特性
- 不做动画/过渡效果（后续优化）
- 不做暗色模式适配

## Decisions

### 1. Android 字体方案

**决定**: 使用 Google Fonts 的 Space Grotesk 字体文件，放入 `res/font/`，通过 XML `fontFamily` 属性引用。

**理由**: 设计稿标题使用 Space Grotesk（fontSize=22, fontWeight=600, letterSpacing=-0.5），Android 默认字体无法还原。Inter 作为正文字体与 Android 默认 sans-serif 接近，可不额外引入。

### 2. Android 颜色/样式对齐策略

**决定**: 在 `colors.xml` 中统一定义设计稿色值，所有 layout XML 直接引用；用 drawable XML 定义状态卡片背景（圆角+边框+填充色）。

**色值映射**:

| 设计稿变量 | 色值 | 用途 |
|-----------|------|------|
| $bg-primary | #FFFFFF | 页面背景 |
| $text-primary | #18181B | 主文字 |
| $text-secondary | #71717A | 次要文字 |
| $text-muted | #A1A1AA | 辅助文字/标签 |
| $accent | #22C55E | 强调色/已连接 |
| $accent-light | #F0FDF4 | 已连接卡片背景 |
| $border | #E4E4E7 | 分割线/卡片边框 |
| $error-light | #FEF2F2 | 未配对卡片背景 |
| $error-border | #FECACA | 未配对卡片边框 |
| $bg-muted | #F4F4F5 | 调试日志背景 |

### 3. Android 布局对齐范围

按设计稿逐屏对齐：

| 设计稿屏幕 | 对应文件 | 核心调整 |
|-----------|---------|---------|
| Android — Main (Paired) | activity_main.xml + MainActivity.kt | 状态卡片绿色样式、同步列表圆角卡片+分割线、调试日志样式 |
| Android — Main (Unpaired) | activity_main.xml + MainActivity.kt | 状态卡片红色样式+扫码按钮、提示文案 |
| Android — Settings | activity_settings.xml + SettingsActivity.kt | 三个分组（设备配对/权限状态/关于）、卡片圆角+分割线样式 |
| Android — Scan QR | activity_scan.xml | 取景框+扫描线+提示文案位置对齐 |
| Android — Notifications | GhostClipService.kt | 已配对/未配对/收到剪贴板三种通知样式 |

### 4. Mac 前端对齐范围

| 设计稿屏幕 | 对应区域 | 核心调整 |
|-----------|---------|---------|
| Mac — Menu Bar Dropdown | app.js renderDropdown() + styles.css | 方向箭头颜色（绿色入/黄色出）、时间戳显示、操作区按钮图标+文案 |
| Mac — Settings Window | app.js renderSettings() + styles.css | 设备配对区（状态+MAC标识+QR按钮）、通知开关描述文案 |
| Mac — QR Code Popup | app.js renderQrPopup() + styles.css | title bar 样式、设备标识标签 |
| Mac — QR Popup (Settings) | app.js + styles.css | Settings 内嵌 QR 弹窗（带返回按钮） |
| Mac — Notification Banner | 已通过 macOS 原生通知实现 | 不做额外调整 |

### 5. 同步列表渲染对齐

**Android**: 设计稿要求同步列表为圆角卡片+内部分割线，每项有图标+内容+时间。当前 MainActivity 动态添加 TextView，需改为 RecyclerView 或保持 LinearLayout 但样式对齐。

**决定**: 保持 LinearLayout 动态添加方案（列表项不多），但为每项创建独立 layout item 模板，包含方向图标+内容+时间戳。

**Mac**: 设计稿 Dropdown 同步列表有方向箭头（↙ 绿色=接收, ↗ 黄色=发送）+ 时间戳。需要在 renderDropdown 中添加方向和时间信息。

## Risks / Trade-offs

- **[Space Grotesk 字体包大小]** → APK 增加约 100KB，可接受
- **[Android 低版本兼容]** → minSdk 29 足以支持所有用到的 XML 属性
- **[Mac 前端改动影响功能]** → 仅调整渲染和样式，不改数据流和事件处理
