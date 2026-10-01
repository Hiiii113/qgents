# Qgents

QG 工作室暑假末期考核项目。

Qgents 是一个支持群聊和代码交付的 Agent 平台。普通消息只用于聊天；`@Agent` 才启动对应 Agent。
通用 Agent、Coding Agent 与自定义 Agent 共用项目自己的 loop：模型调用工具时执行并继续，
没有工具调用的文本回复即结束，最多执行配置的轮次。模型按需调用 subagent，不设置固定的计划、编码、测试、审查阶段。

模型调用 `prepare_workspace` 或用户显式调用 Task API 时创建 Task，并分配固定工作区。
`list_tasks` / `use_workspace` 或 Task 的 `/start` API 继续已有工作，复用原工作区。
代码工具复用沙箱 Worker。任务结束只回复执行结果；用户明确要求提交 MR 时，模型调用
`workspace_repositories` / `create_merge_request` 提交当前修改、推送分支，并直接在 GitHub 创建或复用 PR。

## Agent 实现

- `agent/runtime/AgentLoop` 管理模型调用、工具结果回传和轮次上限。
- `agent/runtime/AgentChatService` 管理聊天上下文、AgentRun、同步 subagent 和可选代码工作区。
- Spring AI 仅提供底层 `ChatModel` 及工具 schema，不负责 loop、阶段调度或自动执行工具；已移除 LangGraph4j。
- `list_agents` / `call_agent` 提供委托，子 Agent 有独立输入，共享根运行的工作区。
- 文件、开发命令、技能与历史查询工具沿用现有实现；工作区工具在准备完成后的下一轮开放。
- `app.agent.max-rounds`、`max-depth`、`max-subagents` 分别限制模型轮次、委托深度和总委托数。
- 群内运行记录查询：`GET /api/v1/projects/{projectId}/groups/{groupId}/agent-runs`。

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

- API：`agent`（loop、Agent 管理和模型工具）、`chat`（群组、消息、上下文）、`task`、`workspace`、`sandbox`（Worker 客户端）、`github`、`mergerequest`、`team`、`project`、`auth`、`user`、`attachment`、`skill`、`memory`、`notification`、`realtime`。
- Worker：`sandbox`（容器和租约）、`workspace`（工作区及 Git 操作）、`tool`（文件和开发命令工具）。
- `common`：跨业务的响应、异常、UUID、幂等处理和基础配置；业务代码放回所属业务包。

### 两个服务的职责边界

| 服务 | 职责 | 端口 | 数据库 |
|---|---|---|---|
| `qgents-api` | Team / Project 隔离、群聊与任务编排、GitHub 集成、Task 工作区与 GitHub PR、鉴权与通知 | 8080（API）、8081（管理端点） | `qgents` |
| `qgents-sandbox-worker` | Workspace Manager（bare Git Store 上的 linked worktree 与受控 Git 操作）、Sandbox Manager（沙箱创建与受限文件/命令工具） | 8091 | `qgents_sandbox_worker` |

`qgents-sandbox-worker` 持有 Docker Socket 与工作区挂载权限，安全边界高于主后端，因此独立打包、
独立数据库、独立部署。Agent 沙箱镜像不安装 Git，Worker 镜像保留 Git CLI。

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

# 运行测试
.\mvnw.cmd test
.\mvnw.cmd -pl qgents-sandbox-worker test
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
