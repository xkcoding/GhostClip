## Why

Landing page 即将上线，需要无侵入式的访问数据统计来了解用户行为（页面浏览、访问来源、设备分布）。Cloudflare Web Analytics 无 cookie、不影响性能，与 Cloudflare Pages 部署天然集成，是最轻量的选择。

## What Changes

- 在 `site/index.html` 中集成 Cloudflare Web Analytics beacon script
- 需要在 Cloudflare 面板创建 Web Analytics 站点并获取 token

## Capabilities

### New Capabilities
- `cf-analytics-integration`: 在 landing page 中集成 Cloudflare Web Analytics beacon，页面加载时自动上报访问数据

### Modified Capabilities

（无）

## Impact

- `site/index.html`: 在 `</body>` 前添加一行 beacon script
- Cloudflare 面板：需要创建 Web Analytics 站点获取 site token
- 无 API 变更、无依赖变更
