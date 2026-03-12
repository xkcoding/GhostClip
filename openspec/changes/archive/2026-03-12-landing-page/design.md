## Context

GhostClip 是 Android 与 Mac 之间的文本剪贴板同步工具，目前缺少面向终端用户的产品介绍页面。目标用户是从 iOS 切换到 Android 但仍使用 Mac 的人群，核心痛点是失去了 Apple 生态的 Handoff 剪贴板体验。

现有资源：
- 品牌色 #22C55E，字体 Space Grotesk（标题）+ Inter（正文）
- Logo：可爱的绿色小幽灵，holding 一封信
- 设计文件：`design/ghostclip-ui.pen`

## Goals / Non-Goals

**Goals:**
- 用一个纯静态页面清晰传达 GhostClip 的价值、使用方式、下载入口
- 极简工具风（Arc Browser 风格），大量留白，克制动效
- 通过 GitHub Actions 自动部署到 Cloudflare Pages
- 集成 Cloudflare Web Analytics 无侵入式埋点
- 页面叙事线引导用户从痛点共鸣到下载行动

**Non-Goals:**
- 不做多语言（仅中文）
- 不做文档站 / 博客
- 不做深色模式（首版）
- 不使用前端框架（纯 HTML/CSS/JS）
- 不做在线演示 / 交互式 demo

## Decisions

### 1. 纯静态 HTML/CSS/JS，无构建步骤

**选择**: 不使用 Astro/Next.js 等框架，纯手写静态页面。

**理由**: 页面只有一个 `index.html`，没有路由、没有组件复用需求。引入框架会增加构建复杂度，对单页面没有收益。

**备选**: Astro（SSG）—— 如果未来需要加文档/博客可以迁移，但当前 YAGNI。

### 2. 目录结构 `site/`

**选择**:
```
site/
├── index.html
├── style.css
├── script.js          # 极简交互（scroll fade-in）
└── assets/
    ├── logo.svg       # 从 design/images/ 转换
    └── favicon.ico
```

**理由**: `site/` 最短且语义明确，和现有 `mac/`、`android/`、`worker/` 平级。

### 3. 极简工具风视觉系统

**选择**:
- 色彩：#22C55E（品牌绿）+ #111827（深灰文字）+ #F9FAFB（背景灰）
- 字体：Space Grotesk（标题）+ Inter（正文），Google Fonts 引入
- 动效：仅 scroll fade-in，无花哨动画
- 布局：单列居中，max-width 720px 内容区
- 每个 section 充分留白，一屏一个核心信息

### 4. 页面叙事线

七个 section 按情绪线组织：**共鸣 → 理解 → 信任 → 行动**

1. **Hero**: slogan + 副标题 + 下载按钮
2. **痛点共鸣**: 三个具体场景（验证码/地址/文案）
3. **方案过渡**: 一句话介绍 GhostClip
4. **同步方式**: 双向同步的详细说明（重点区域）
5. **设计理念**: "只同步你想同步的" —— 隐私/省电/精准
6. **快速上手**: 配对流程 + 权限说明
7. **下载 + Footer**: 安装方式 + 开源信息

### 5. 部署方案：GitHub Actions + Cloudflare Pages

**选择**: GitHub Actions 手动 push 到 Cloudflare Pages（wrangler pages deploy）。

**理由**: 比 Cloudflare 直连更可控，未来如需加构建步骤（压缩、sitemap）可直接扩展。

**配置**: 需要以下 GitHub Secrets：
- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`

**触发条件**: `site/` 目录文件变更 + push 到 main 分支。

### 6. Cloudflare Web Analytics

**选择**: 使用 Cloudflare 的无侵入式 Web Analytics，不用 Google Analytics。

**理由**: 无 cookie、不影响页面性能、与 Cloudflare Pages 天然集成。只需在 HTML 中加一行 script 标签。

## Risks / Trade-offs

- **[Logo 素材格式]** 现有 logo 是 PNG，landing page 最好用 SVG → 可用 PNG 先上线，后续替换
- **[Google Fonts 加载]** 中国用户可能加载慢 → 设置 `font-display: swap`，本地字体做 fallback
- **[单页面 SEO]** 纯静态单页不利于搜索引擎 → 首版不关注 SEO，加 meta 标签即可
- **[Cloudflare Pages 项目创建]** 需要手动在 Cloudflare 面板创建项目 → 在 tasks 中标注为前置步骤
