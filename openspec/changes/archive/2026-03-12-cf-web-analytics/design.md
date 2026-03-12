## Context

GhostClip landing page（`site/index.html`）即将通过 Cloudflare Pages 部署上线，需要集成访问数据统计。页面为纯静态 HTML/CSS/JS，无构建步骤。

## Goals / Non-Goals

**Goals:**
- 在 landing page 中集成 Cloudflare Web Analytics，无侵入式采集访问数据
- 零配置维护，部署后自动工作

**Non-Goals:**
- 不做自定义事件追踪（首版仅页面级统计）
- 不集成 Google Analytics 或其他第三方统计
- 不做 A/B 测试或用户行为分析

## Decisions

### 1. 使用 Cloudflare Web Analytics（而非 Google Analytics）

**选择**: Cloudflare Web Analytics beacon script

**理由**: 无 cookie、不影响页面性能、隐私友好，与 Cloudflare Pages 天然集成。GhostClip 强调隐私保护，统计工具也应保持一致的价值观。

**备选**: Google Analytics — 功能更强但需要 cookie 同意横幅，与产品隐私理念冲突。

### 2. Beacon script 放置位置

**选择**: 在 `</body>` 标签之前插入 beacon script。

**理由**: Cloudflare 官方推荐位置，不阻塞页面渲染，确保页面内容优先加载。

### 3. Site token 管理

**选择**: 将 Cloudflare 分配的 site token 直接写入 HTML（非敏感信息）。

**理由**: Web Analytics token 是公开的客户端标识符，不是 API 密钥，无安全风险。所有使用 Cloudflare Web Analytics 的站点都是这样做的。

## Risks / Trade-offs

- **[Token 需手动获取]** 需在 Cloudflare 面板手动创建 Web Analytics 站点获取 token → 在 tasks 中标注为前置步骤，并用占位符标记
- **[中国访问]** Cloudflare 的 beacon 域名在中国可能加载较慢 → 使用 `defer` 加载，不影响页面主体渲染
