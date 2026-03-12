## ADDED Requirements

### Requirement: Hero section 展示产品核心定位
页面首屏 SHALL 包含 GhostClip logo、主标题（"选了 Android，不必放弃 Mac 体验"）、副标题（说明文本剪贴板同步 + 局域网直连）、以及两个下载按钮（Mac 版 / Android 版）。

#### Scenario: 用户首次访问页面
- **WHEN** 用户打开 landing page
- **THEN** 首屏展示 logo、slogan "选了 Android，不必放弃 Mac 体验"、副标题 "Android 与 Mac 之间的文本剪贴板同步，局域网直连，数据不经云端"、"下载 Mac 版" 和 "下载 Android 版" 两个按钮

#### Scenario: 点击下载按钮
- **WHEN** 用户点击 "下载 Mac 版" 或 "下载 Android 版"
- **THEN** 页面跳转至 GitHub Releases 页面对应的最新版本下载

### Requirement: 痛点共鸣 section 建立情感连接
页面 SHALL 包含一个 section，标题为 "从 iPhone 换到 Android 后，有些事突然变麻烦了"，展示三个具体痛点场景卡片。

#### Scenario: 展示痛点场景
- **WHEN** 用户滚动到痛点 section
- **THEN** 展示三个场景卡片：(1) 收到验证码只能手动输入到 Mac (2) Mac 复制地址想发到手机导航只能靠微信 (3) 手机看到好文案想在电脑编辑只能自己发给自己

### Requirement: 方案过渡 section 引出产品
页面 SHALL 包含简短过渡，用一两句话介绍 GhostClip 是什么：给 Android + Mac 补上剪贴板同步这块拼图。

#### Scenario: 展示方案过渡
- **WHEN** 用户滚动到方案 section
- **THEN** 展示 "GhostClip —— 给 Android + Mac 补上这块拼图" 及简短描述

### Requirement: 同步方式 section 详解双向同步操作
页面 SHALL 包含同步方式详解 section，分别说明 Android→Mac 和 Mac→Android 两个方向的同步方式。

#### Scenario: 展示 Android 到 Mac 同步方式
- **WHEN** 用户查看同步方式 section
- **THEN** 展示两种方式：(1) 点击悬浮球（推荐，体验最好）(2) 切回 GhostClip 前台（自动读取同步），并说明 Mac 自动接收后 Cmd+V 即可粘贴

#### Scenario: 展示 Mac 到 Android 同步方式
- **WHEN** 用户查看同步方式 section
- **THEN** 展示两种方式：(1) 快捷键 Cmd+Shift+C (2) Menu Bar 下拉点击 "发送剪贴板到 Android"，并说明手机收到通知后点击即可使用

### Requirement: 设计理念 section 解释按需同步的设计决策
页面 SHALL 包含设计理念 section，标题为 "只同步你想同步的"，将按需同步设计解释为隐私保护、省电、精准控制三个优势。

#### Scenario: 展示设计理念
- **WHEN** 用户查看设计理念 section
- **THEN** 展示三个要点：(1) 隐私——密码、聊天记录不会被无差别搬运 (2) 省电——不在后台持续监听 (3) 精准——用户决定什么时候同步什么内容

### Requirement: 快速上手 section 展示配对流程和权限说明
页面 SHALL 包含快速上手 section，展示 30 秒配对流程和建议开启的权限列表。

#### Scenario: 展示配对流程
- **WHEN** 用户查看快速上手 section
- **THEN** 展示配对步骤：(1) Mac 安装 (2) Android 安装 APK (3) 同一 WiFi 下 Mac 显示二维码 (4) 手机扫码连接

#### Scenario: 展示权限说明
- **WHEN** 用户查看权限说明
- **THEN** 展示三项权限：相机（使用时允许，仅扫码配对用）、剪贴板写入（始终允许，接收同步内容）、通知（开启，查看 Mac 下发的内容）

### Requirement: 下载安装 section 提供完整安装指引
页面 SHALL 包含下载安装 section，提供 Mac（Homebrew + DMG）和 Android（APK）的安装方式。

#### Scenario: 展示 Mac 安装方式
- **WHEN** 用户查看下载 section
- **THEN** 展示 Homebrew 命令 `brew install --cask xkcoding/tap/ghostclip` 和手动下载 DMG 链接

#### Scenario: 展示 Android 安装方式
- **WHEN** 用户查看下载 section
- **THEN** 展示 APK 下载链接（指向 GitHub Releases）

### Requirement: Footer 包含项目信息
页面 SHALL 包含 footer，展示开源信息、GitHub 链接和 MIT License。

#### Scenario: 展示 footer 信息
- **WHEN** 用户滚动到页面底部
- **THEN** 展示 GitHub 仓库链接、MIT 开源协议标注

### Requirement: 极简工具风视觉设计
页面 SHALL 采用极简工具风设计：品牌色 #22C55E、深灰文字 #111827、浅灰背景 #F9FAFB、Space Grotesk 标题字体、Inter 正文字体、大量留白、单列居中布局。

#### Scenario: 视觉风格一致性
- **WHEN** 用户浏览页面任意部分
- **THEN** 页面风格统一为极简工具风，无花哨动效（仅 scroll fade-in），每个 section 有充足留白

### Requirement: Cloudflare Web Analytics 埋点
页面 SHALL 集成 Cloudflare Web Analytics，用于无侵入式访问数据统计。

#### Scenario: 页面加载时初始化埋点
- **WHEN** 页面加载完成
- **THEN** Cloudflare Web Analytics beacon script 正确加载并上报数据

### Requirement: 响应式布局适配
页面 SHALL 在桌面端和移动端均正常展示，采用响应式设计。

#### Scenario: 桌面端浏览
- **WHEN** 用户在 >= 768px 宽度设备上访问
- **THEN** 页面内容区居中展示，max-width 720px，左右留白

#### Scenario: 移动端浏览
- **WHEN** 用户在 < 768px 宽度设备上访问
- **THEN** 页面内容全宽展示，适当 padding，文字和按钮大小适配移动端
