## 1. 项目脚手架

- [x] 1.1 创建 `site/` 目录结构：`index.html`、`style.css`、`script.js`、`assets/`
- [x] 1.2 准备 logo 素材（PNG 放入 `site/assets/`，制作 favicon）

## 2. 页面骨架与全局样式

- [x] 2.1 编写 HTML 骨架：七个 section 的语义化结构（hero / pain-points / solution / sync-methods / philosophy / quickstart / download + footer）
- [x] 2.2 引入 Google Fonts（Space Grotesk + Inter），设置全局 CSS 变量（品牌色 #22C55E、文字色 #111827、背景色 #F9FAFB）
- [x] 2.3 实现极简工具风基础样式：单列居中 max-width 720px、section 间距、排版层级
- [x] 2.4 实现响应式布局：桌面端居中留白 + 移动端全宽适配（断点 768px）

## 3. 各 Section 内容填充

- [x] 3.1 Hero section：logo + slogan + 副标题 + 双下载按钮（链接到 GitHub Releases）
- [x] 3.2 痛点共鸣 section：标题 + 三个场景卡片（验证码 / 地址 / 文案）
- [x] 3.3 方案过渡 section：一句话介绍 GhostClip
- [x] 3.4 同步方式 section：Android→Mac（悬浮球 + 前台切换）、Mac→Android（快捷键 + Menu Bar 下拉）
- [x] 3.5 设计理念 section："只同步你想同步的" + 隐私/省电/精准三个要点
- [x] 3.6 快速上手 section：30 秒配对流程 + 权限说明表格（相机/剪贴板/通知）
- [x] 3.7 下载安装 section：Mac（Homebrew 命令 + DMG 链接）+ Android（APK 链接）
- [x] 3.8 Footer：GitHub 链接 + MIT License

## 4. 交互与埋点

- [x] 4.1 实现 scroll fade-in 动画（`script.js`，IntersectionObserver）
- [x] 4.2 集成 Cloudflare Web Analytics beacon script

## 5. CI 部署

- [x] 5.1 创建 `.github/workflows/deploy-site.yml`：on push to main（paths: site/**）→ wrangler pages deploy
- [x] 5.2 文档记录需要配置的 GitHub Secrets（CLOUDFLARE_API_TOKEN、CLOUDFLARE_ACCOUNT_ID）和 Cloudflare Pages 项目名称
