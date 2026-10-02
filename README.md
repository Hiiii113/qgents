# Qgents

QG 工作室暑假末期考核项目。

产品方向与持续协作依据见 [docs/target.md](docs/target.md)。下文介绍当前实现；GitHub App 与仓库导入后端已实现，前端接入、真实环境联调和结构化成果展示仍需后续完善。

Qgents 是一个支持群聊和代码交付的 Agent 平台。普通消息只用于聊天；`@Agent` 才启动对应 Agent。
通用 Agent、Coding Agent 与自定义 Agent 共用项目自己的 loop：模型调用工具时执行并继续，
没有工具调用的文本回复即结束，最多执行配置的轮次。模型按需调用 subagent，不设置固定的计划、编码、测试、审查阶段。

消息发送只保留 `type`、`content`、`mentions`、`replyToId`、`clientMessageId`。
回复用普通消息加 `replyToId`，不再支持 QUOTE、replyText 或客户端拼接的引用摘要。
项目创建时自动创建唯一默认群聊；群成员来自项目成员，不区分主群或需求群。

代码工具一开始即可由模型调用，首次调用才准备 Workspace 和 Sandbox。
问答、历史检索、Skill 激活及 `github_request` 不创建代码环境。
`list_workspaces` / `create_workspace` / `use_workspace` 管理独立环境；创建或选择环境不启动沙箱，
未选择环境时首次代码工具自动创建空工作区。Agent 自主 clone 仓库，默认使用远端默认/base 分支。
一次运行可以切换 Workspace，后续工作也可以复用已有目录。运行结束释放 Sandbox，保留工作目录。

工作目标与成果保留在聊天消息中，AgentRun 记录执行状态，不维护独立 Task。
继续工作时读取引用消息及聊天历史，再按需选择已有 Workspace。
`cancel_agent` 工具和消息的 `cancel-agent` 接口可停止该消息已启动的执行。
AgentRun 保存最近使用的 Workspace，Worker 执行记录保存每次实际使用的环境。
`run_command` 在工作区中执行参数数组命令，并返回真实 stdout/stderr 和退出码。
分支、commit、push 和 PR 由 Agent 按用户目标调用工具完成，系统不自动执行 Git 交付。

### 可选 GitHub 能力

团队通过 GitHub App 安装连接 GitHub，再选择授权仓库导入项目。通过
`PUT /api/v1/teams/{teamId}/agents/{agentId}/github-capability` 设置
`{"enabled":true}` 开启 GitHub 能力，使用当前项目导入仓库的 App 安装凭证；`false` 关闭能力。默认 Agent 由 Team Owner 配置，
自定义 Agent 由创建者配置，也可在创建或编辑时提供 `githubEnabled`。
安装配置、接口和操作示例见 [GitHub App 与仓库导入](docs/github-app.md)。不提供个人 OAuth。
App 凭证每次操作按安装和目标仓库获取，不进入提示词或工具参数。

- `github_request` 访问 GitHub REST API，如查询仓库、创建 PR、查询或合并 PR。
- `github_repositories` 列出项目已导入仓库，无需创建执行环境。
- `github_git` 支持带认证的 fetch、pull 等 Git 操作。
- `github_clone` / `github_push` 使用当前 Agent 的凭证，在选定 Workspace 内操作选择的仓库。
- 仓库基线省略时遵循远端默认分支；分支、提交和其他 Git 操作用 `run_command`。
- Git 操作使用临时进程环境认证，不把凭据写入 remote URL 或仓库配置。

本次使用项目已有 Tool 机制提供附加能力，不引入 MCP 服务框架；技能仍通过现有 Skill 工具按需激活。

## Agent 实现

- `agent/runtime/AgentLoop` 管理模型调用、工具结果回传和轮次上限。
- `agent/runtime/AgentChatService` 管理聊天上下文、AgentRun、同步 subagent 和可选代码工作区。
- Spring AI 仅提供底层 `ChatModel` 及工具 schema，不负责 loop、阶段调度或自动执行工具；已移除 LangGraph4j。
- `list_agents` / `call_agent` 提供委托，子 Agent 有独立输入，共享根运行的工作区。
- 文件、命令和 Git 工具通过 Worker 共享按需沙箱环境入口；技能及历史检索直接执行。`read_chat_message` 可展开消息全文及回复关系。
- `app.agent.max-rounds`、`max-depth`、`max-subagents` 分别限制模型轮次、委托深度和总委托数。

本次改造以新的建表脚本为准，不保留旧阶段执行 API 的兼容行为。

## 仓库结构

本仓库是一个 Maven 多模块工程，包含**两个独立部署的 Spring Boot 服务**，二者运行期不共享类路径，
只通过 OpenAPI 契约进行 HTTP 交互。

```text
Qgents/
├── pom.xml                       # 父 POM（qgents-parent）：聚合模块 + 统一依赖/插件版本
├── qgents-api/                   # 主后端服务（对外唯一 API）
│   ├── pom.xml
│   └── src/                      # 包 qg.qgent.*
├── qgents-sandbox-worker/        # 沙箱执行面服务（内部接口）
│   ├── pom.xml
│   ├── src/                      # 包 qg.qgent.sandboxworker.*
│   └── Dockerfile.production
├── contracts/                    # 跨服务契约（sandbox-worker-openapi.yaml）
├── sandbox-images/dev-tools/     # 沙箱内开发工具镜像
├── Dockerfile.production         # 主后端镜像
└── docker-compose.production.yml # 生产编排
```

### Java 分包

源码按业务组织，每个业务内部按需使用 `controller`、`service`、`dto`、`entity`、`mapper`、`config` 等子包。测试与被测代码保持相同包路径。

- API：`agent`（loop、Agent 管理和模型工具）、`chat`（群组、消息、上下文）、`workspace`、`sandbox`（Worker 客户端）、`team`、`project`、`auth`、`user`、`attachment`、`skill`、`memory`、`notification`、`realtime`。
- Worker：`sandbox`（容器和租约）、`workspace`（空工作区管理）、`tool`（文件和开发命令工具）。
- `common`：跨业务的响应、异常、UUID、幂等处理和基础配置；业务代码放回所属业务包。

### 两个服务的职责边界

| 服务 | 职责 | 端口 | 数据库 |
|---|---|---|---|
| `qgents-api` | Team / Project 隔离、群聊、Agent loop 与独立工作区、鉴权与通知 | 8080（API）、8081（管理端点） | `qgents` |
| `qgents-sandbox-worker` | 空工作区、沙箱创建与文件/命令执行 | 8091 | `qgents_sandbox_worker` |

`qgents-sandbox-worker` 持有 Docker Socket 与工作区挂载权限，安全边界高于主后端，因此独立打包、
独立数据库、独立部署。Agent 沙箱镜像安装 Git，命令在沙箱中执行。

## 环境要求

- JDK 21（项目统一 Java 21，不得降级）
- Maven 3.9+，或直接使用仓库自带的 `mvnw` / `mvnw.cmd`
- MySQL 8.0.16+、Redis（本地开发）
- Docker（仅构建/运行沙箱执行面时需要）

## 构建与测试

在仓库根目录执行，父 POM 会统一管理两个模块：

```powershell
# 构建全部模块
.\mvnw.cmd clean package -DskipTests

# 只构建单个模块
.\mvnw.cmd -pl qgents-api clean package -DskipTests
.\mvnw.cmd -pl qgents-sandbox-worker clean package -DskipTests

# 运行单元测试（不要求数据库和 Docker）
.\mvnw.cmd test
.\mvnw.cmd -pl qgents-sandbox-worker test

# 外部服务集成测试需初始化新版数据库，并显式启用
$env:QGENTS_INTEGRATION_TESTS="true"
.\mvnw.cmd -pl qgents-api -Dtest=FixedAccountAuthIntegrationTest test
```

## 运行

两个服务都通过环境变量注入配置，各模块工作目录下可放置独立 `.env`（`spring.config.import`
按相对工作目录解析 `.env`，该文件不入版本控制）：

- 主后端：工作目录为 `qgents-api/`，读取 `qgents-api/.env`
- Worker：工作目录为 `qgents-sandbox-worker/`，读取 `qgents-sandbox-worker/.env`

生产部署使用 `docker-compose.production.yml`，其变量替换读取**仓库根目录**的 `.env`；
可用 `.env.example` 作为模板。主后端与 Worker 所需密钥必须通过环境变量或受控注入提供，
不得写入配置文件或提交。

## 文档

- Worker 内部契约：`contracts/sandbox-worker-openapi.yaml`
- Worker 使用说明：`qgents-sandbox-worker/README.md`
- Agent 协作与工程约束：`AGENTS.md`
