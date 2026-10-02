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
AgentRun 保存最近使用的 Workspace，Sandbox 执行记录保存每次实际使用的环境。
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
- 文件、命令和 Git 工具通过 Sandbox 模块共享按需沙箱环境入口；技能及历史检索直接执行。`read_chat_message` 可展开消息全文及回复关系。
- `app.agent.max-rounds`、`max-depth`、`max-subagents` 分别限制模型轮次、委托深度和总委托数。

本次改造以新的建表脚本为准，不保留旧阶段执行 API 的兼容行为。

## 仓库结构

一个 Spring Boot 应用；聊天、Agent 和 Sandbox 按业务分包，工具直接调用 Java 服务。

```text
Qgents/
├── pom.xml                         # 单应用依赖与构建
├── src/main/java/qg/qgent/
│   ├── agent/                      # Agent loop、模型工具和运行记录
│   ├── chat/                       # 群聊、消息和上下文
│   ├── workspace/                  # 工作区业务记录与写入租约
│   ├── sandbox/
│   │   ├── config/                 # Docker、执行线程池和资源配置
│   │   ├── runtime/                # 容器创建、命令执行和回收
│   │   ├── service/                # 会话与 Workspace 工具适配
│   │   ├── execution/              # 工具处理器、执行记录、完成通知和日志
│   │   └── filesystem/             # 工作目录、路径和补丁操作
│   └── ...                         # 其他现有业务领域
├── src/main/resources/             # 统一配置和数据库建表语句
├── src/test/                       # 测试与源码包路径一致
├── sandbox-images/dev-tools/       # 用户代码所在的沙箱镜像
├── Dockerfile.production
└── docker-compose.production.yml
```

业务内部按需使用 `controller`、`service`、`dto`、`entity`、`mapper`、`config` 等子包。
文件工具在调用线程执行；命令使用独立线程池，短等待后返回句柄，后续可读取输出或取消。
等待使用进程内完成通知，执行记录与其他业务使用同一个 MySQL 数据源。

应用负责管理 Docker 容器，用户命令仍在 Sandbox 容器内运行。首次代码工具才准备环境，运行结束销毁容器并保留 Workspace。
应用容器需要 Docker Socket 组权限及工作目录挂载；应用重启会影响聊天和正在进行的 Agent 执行。

## 环境要求

- JDK 21、Maven 3.9+，或仓库自带的 `mvnw` / `mvnw.cmd`。
- MySQL 8.0.16+、Redis；用 `src/main/resources/db/qgents_schema.sql` 初始化一个 `qgents` 数据库，包含 Sandbox 执行表。
- Docker 用于实际代码环境。`SANDBOX_RUNTIME=fake` 仅用于不使用真实容器的测试与联调。

## 构建与测试

在仓库根目录执行：

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd package -DskipTests

# 外部服务集成测试需初始化数据库并显式启用。
$env:QGENTS_INTEGRATION_TESTS="true"
.\mvnw.cmd -Dtest=FixedAccountAuthIntegrationTest test
```

产物为 `target/qgents-0.0.1-SNAPSHOT.jar`。

## 运行

从根目录加载 `.env`（不入版本控制），统一配置数据库、模型、GitHub 和 Sandbox。
本地执行 `.\mvnw.cmd spring-boot:run`，或 `java -jar target/qgents-0.0.1-SNAPSHOT.jar`。
真实代码工具还需要先构建开发镜像：`docker build -t qgents/sandbox-dev-tools:0.2.0 sandbox-images/dev-tools`。

生产部署使用 `docker-compose.production.yml`，以根目录 `.env.example` 为配置模板。
Compose 中 API 管理沙箱并挂载持久工作目录；`QGENTS_DATA_ROOT` 必须是宿主机绝对路径，`DOCKER_GID` 对应宿主机 Docker 组。
应用端口为 8080，管理端点端口为 8081；保留 Redis、代理与开发镜像相关服务。

## 文档

- 产品愿景与实现差距：`docs/target.md`
- Sandbox 工具与 DeepSeek Harness 对照：`docs/sandbox-tools.md`
- GitHub App 接入：`docs/github-app.md`
- 协作约定：`AGENTS.md`
