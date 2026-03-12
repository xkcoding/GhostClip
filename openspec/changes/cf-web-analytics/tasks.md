## 1. 前置准备

- [x] 1.1 在 Cloudflare 面板创建 Web Analytics 站点，获取 site token

## 2. 集成 Beacon Script

- [x] 2.1 在 `site/src/layouts/Layout.astro` 的 `</body>` 标签前添加 Cloudflare Web Analytics beacon script（`defer` 加载）
- [x] 2.2 将获取的 site token 填入 `data-cf-beacon` 属性

## 3. 验证

- [x] 3.1 本地确认 beacon script 标签存在且格式正确（位于 Layout.astro:60-63）
- [ ] 3.2 部署后在 Cloudflare Web Analytics 面板确认数据开始上报
