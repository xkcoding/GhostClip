## Why

GhostClip 缺少一个面向用户的介绍页面。目前用户只能通过 GitHub README 了解产品，这对非技术用户（特别是从 iOS 切换到 Android 但仍使用 Mac 的用户）来说门槛过高。需要一个极简风格的 landing page，部署在 Cloudflare Pages 上，清晰传达产品价值、使用方式和下载入口。

## What Changes

- 新增 `site/` 目录，包含纯静态 landing page（HTML/CSS/JS）
- 新增 GitHub Actions workflow，自动部署 `site/` 到 Cloudflare Pages
- 页面包含完整叙事线：痛点共鸣 → 方案介绍 → 同步方式详解 → 设计理念 → 快速上手 → 下载
- 集成 Cloudflare Web Analytics 埋点
- 极简工具风设计，沿用 #22C55E 品牌色 + Space Grotesk / Inter 字体
- 仅中文版本

## Capabilities

### New Capabilities
- `landing-page-content`: Landing page 的内容结构与叙事——hero、痛点共鸣、同步方式详解、设计理念、快速上手与权限说明、下载安装
- `landing-page-ci`: GitHub Actions 自动部署 site/ 到 Cloudflare Pages 的 CI 配置

### Modified Capabilities

（无现有 capability 需要修改）

## Impact

- 新增 `site/` 目录（HTML/CSS/JS 静态文件）
- 新增 `.github/workflows/deploy-site.yml` CI workflow
- 需要 Cloudflare Pages 项目配置（API Token、Account ID 等 secrets）
- 不影响 mac/、android/、worker/ 现有代码
