# GitHub App 与项目仓库导入

团队通过 GitHub App 连接 GitHub，项目管理员选择 App 已授权的仓库导入项目。导入只保存仓库清单，不会 clone、分配 Workspace 或执行自动交付。不使用个人 OAuth。

## 配置

在 GitHub App 设置中启用安装回调，将 Setup URL 设为本系统的 `/api/v1/integrations/github/callback`。配置 Webhook URL 为 `/api/v1/integrations/github/webhook`，订阅安装和仓库授权变化。回调需要 GitHub 返回 `installation_id` 和安装入口产生的 `state`。

API 环境变量：

- `GITHUB_APP_ID`：App ID。
- `GITHUB_APP_SLUG`：App URL 中的名称。
- `GITHUB_APP_PRIVATE_KEY_PATH`：可读取的 PEM 私钥路径，支持 PKCS#1 和 PKCS#8。容器部署需挂载私钥文件。
- `GITHUB_APP_CALLBACK_URL`：完整安装回调 URL。
- `GITHUB_WEBHOOK_SECRET`：与 GitHub 配置一致的 Webhook Secret。

App 需要按实际操作配置仓库权限。clone/fetch 需要 Contents 读取，push 需要 Contents 写入，创建和管理 PR 需要 Pull requests 写入。可访问仓库还取决于安装时用户选择的仓库范围。

生产 Compose 已注入以上配置，私钥目录挂载到 `/run/qgents/github`。将 App 私钥放在宿主机 `${QGENTS_DATA_ROOT:-/srv/qgents}/github-app/private-key.pem`；它与系统登录使用的 RSA 私钥是不同文件。此部署中容器私钥路径固定，无需设置宿主机的 `GITHUB_APP_PRIVATE_KEY_PATH`。

Token 和权限范围说明见 [GitHub 官方安装认证文档](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-as-a-github-app-installation)。

开发数据库结构以 `src/main/resources/db/qgents_schema.sql` 为准；包含团队安装、一次性安装回调和项目导入仓库表。代码修改不会自动重建已有数据库。

## 使用步骤

所有用户接口使用 Qgents 登录 Token。项目写接口还需要 `Idempotency-Key`。

1. 团队所有者调用 `POST /api/v1/teams/{teamId}/integrations/github/installations?client=WEB`，取得安装 URL。`client` 可选 WEB 或 MOBILE。
2. 用户打开 URL 安装 App，选择授权仓库。回调通过 10 分钟内有效的一次性 state 保存团队连接，再跳转到 Web 或 Mobile 的 GitHub 集成页面。
3. 团队成员调用 `GET /api/v1/teams/{teamId}/integrations/github/repositories`，获取可导入仓库。返回的 `installationId` 是本地 UUID，`repositoryId` 是 GitHub 数字 ID。
4. 项目管理员调用 `POST /api/v1/projects/{projectId}/repositories`：

```json
{"installationId":"安装记录的UUID","repositoryId":123456}
```

5. 调用 `GET /api/v1/projects/{projectId}/repositories` 查看导入记录；重复导入同一仓库返回已有记录。移除用 `DELETE /api/v1/projects/{projectId}/repositories/{repositoryId}`，此处 ID 是导入记录 UUID，不是 GitHub 数字 ID。
6. 项目 GitHub 能力默认开启。项目管理员可以在项目设置修改，也可以调用项目更新接口：

```text
PATCH /api/v1/projects/{projectId}
```

```json
{"githubEnabled":false}
```

该开关同时控制 Qgent 与本次运行的子 Agent。开启后仍需导入仓库并具备有效团队 App 授权；导入不会自动执行代码或 Git 操作。

## Agent 工具

- `github_repositories`：读取当前项目的导入仓库和默认分支，不启动沙箱。
- `github_request`：使用 `/repos/owner/repo/...` 调用远端 API，支持查询、创建、编辑和合并 PR；目标仓库必须已导入当前项目。例如创建 PR 使用 POST `/repos/owner/repo/pulls`，正文包含 title、head、base。
- `github_clone`：首次需要代码环境时才准备选定 Workspace 和沙箱，未指定基线时 clone 远端默认分支。
- `github_push`：推送当前 HEAD 到指定分支，不自动 commit 或创建 PR。
- `github_git`：带 App 认证执行 fetch/pull 等 Git 操作，需传入目标 `repository: "owner/repo"`。

每次操作从 App 安装取得仅授权目标仓库的短期 Token；不保存 Token 到消息、提示词、remote URL 或仓库配置。Agent 依用户目标决定是否执行写操作。

## 管理与同步

- `GET /api/v1/teams/{teamId}/integrations/github/installations`：查询团队连接。
- `POST /api/v1/teams/{teamId}/integrations/github/installations/{installationId}/sync`：团队所有者手动同步安装信息和导入仓库元数据，移除失去授权的导入记录。
- `DELETE /api/v1/teams/{teamId}/integrations/github/installations/{installationId}`：解除本系统团队连接，并级联移除对应导入记录；不卸载 GitHub 账号上的 App。
- Webhook 同步安装卸载、暂停、恢复和仓库授权变化。重复事件只重复刷新或删除，不创建通知或 PR 镜像。

PR 的状态由 Agent 调用远端 API 查询，不恢复旧 MR 自动交付状态机。GitHub App 未配置时普通聊天和其他 Harness 能力仍可使用。
