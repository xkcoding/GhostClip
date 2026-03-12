## ADDED Requirements

### Requirement: GitHub Actions 自动部署到 Cloudflare Pages
SHALL 提供一个 GitHub Actions workflow，在 `site/` 目录文件变更并 push 到 main 分支时，自动将 `site/` 部署到 Cloudflare Pages。

#### Scenario: site 目录变更触发部署
- **WHEN** 开发者 push 到 main 分支且 `site/**` 路径下有文件变更
- **THEN** GitHub Actions 触发部署 workflow，使用 wrangler pages deploy 将 `site/` 目录发布到 Cloudflare Pages

#### Scenario: 非 site 目录变更不触发部署
- **WHEN** 开发者 push 到 main 分支但 `site/**` 路径下无文件变更
- **THEN** 部署 workflow 不被触发

### Requirement: 部署需要 Cloudflare 认证 secrets
Workflow SHALL 使用 GitHub Secrets 存储 Cloudflare 认证信息，不在代码中硬编码。

#### Scenario: 使用 secrets 认证
- **WHEN** 部署 workflow 执行
- **THEN** 使用 `CLOUDFLARE_API_TOKEN` 和 `CLOUDFLARE_ACCOUNT_ID` 两个 GitHub Secrets 进行认证

### Requirement: 部署成功后可通过 Cloudflare Pages URL 访问
部署完成后，landing page SHALL 可通过 Cloudflare Pages 分配的域名访问。

#### Scenario: 部署成功验证
- **WHEN** 部署 workflow 成功完成
- **THEN** 通过 Cloudflare Pages URL 可访问到最新的 landing page 内容
