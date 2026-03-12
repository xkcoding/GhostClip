## ADDED Requirements

### Requirement: Landing page 集成 Cloudflare Web Analytics beacon
页面 SHALL 在 `</body>` 标签前包含 Cloudflare Web Analytics 的 beacon script，页面加载完成后自动上报访问数据。

#### Scenario: 页面加载时初始化埋点
- **WHEN** 用户访问 landing page 且页面加载完成
- **THEN** Cloudflare Web Analytics beacon script 正确加载并向 Cloudflare 上报页面浏览数据

#### Scenario: Beacon 加载不阻塞页面渲染
- **WHEN** 页面开始加载
- **THEN** beacon script 以 `defer` 方式加载，不阻塞页面主体内容的渲染

### Requirement: Beacon script 使用正确的 site token
Beacon script SHALL 包含 Cloudflare 分配的 site token（`data-cf-beacon` 属性），确保数据上报到正确的 Analytics 站点。

#### Scenario: Token 配置正确
- **WHEN** beacon script 执行
- **THEN** 使用 `data-cf-beacon='{"token": "<SITE_TOKEN>"}'` 格式携带正确的 site token
