# Qgents

QG 工作室暑假末期考核项目。

Qgents 是一个多 Agent 协作的代码交付平台：用户在项目群中提出需求，平台拆解为可追踪的执行层级、
在受控沙箱中读写代码、独立执行测试与审查，最终以 Diff 或 MR 两种模式交付可核验的代码结果。

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

### 两个服务的职责边界

| 服务 | 职责 | 端口 | 数据库 |
|---|---|---|---|
| `qgents-api` | Team / Project 隔离、群聊与任务编排、GitHub 集成、Diff 与 MR 交付、鉴权与通知 | 8080（API）、8081（管理端点） | `qgents` |
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
