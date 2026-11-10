# Qgents

团队协作的云端 Agent 工作台。在项目群聊里 `@` 项目内的 Agent（新建项目自带的默认 Agent，或管理员审批通过的自定义 Agent），它会读取聊天上下文、项目知识与附件，按需拉起 Docker 沙箱读写代码、执行命令，并把分支推回 GitHub。

后端是单个 Java 21 / Spring Boot 应用，同时承载 IM、Agent Harness 与 Sandbox 管理，依赖 MySQL 和 Redis；前端位于相邻的 `../qgents-fronted` 仓库（React 19）。

## 功能

- **项目群聊**：团队 → 项目 → 多个群聊，支持成员、提及、回复、已读与置顶。只有 `@` 已在群内的 Agent 的消息才会启动 Agent，普通聊天不调用模型。
- **Agent Harness**：自研工具循环，默认最多 30 轮、单步最多 4 个并行工具；可后台派生子 Agent（默认上限 8 个），运行中可取消。
- **Docker Sandbox**：用户命令在临时容器中执行（默认 2 核 / 4 GiB，空闲 30 分钟或最长 4 小时后回收）。项目数据目录持久保存 `memory.md`、群记忆 `groups/<groupId>/memory.md`、Agent 私有记忆 `agent-memory/<agentId>/<groupId>.md`、共享 Git 数据与各 Workspace 的 worktree。
- **项目知识**：共享 `memory.md`、项目 Skill、群聊历史检索、图片与文本附件（本地磁盘或阿里云 OSS 直传）。
- **GitHub App**（可选）：团队安装、项目管理员导入仓库；Agent 可读 PR / Issue、拉取代码并推送分支，不自动创建或合并 PR。
- **实时与通知**：项目事件通过 SSE（可用 `Last-Event-ID` 续传）与 WebSocket 推送，站内通知支持可选的 FCM 移动端推送。

## 架构

```text
浏览器（React 19 前端）
   │  HTTP · SSE · WebSocket   /api/v1
   ▼
Spring Boot 应用 ── MySQL 8.4   业务数据、实时事件
   │              └ Redis 7     刷新令牌、验证码、限流
   ├── DeepSeek（OpenAI 兼容协议，Spring AI）
   └── Docker Engine
         └── Sandbox 容器 ── 项目数据卷：memory.md、groups/、agent-memory/、.git、workspaces/
```

业务端口 `8080`；运维端口默认只监听 `127.0.0.1:8081`，暴露 `health`、`info`、`metrics`、`prometheus`。

## 技术栈

| 领域 | 选型 |
| --- | --- |
| 运行时 | Java 21、Spring Boot 4.1 |
| 数据 | MyBatis-Plus、MySQL 8.4、Redis 7 |
| 模型 | Spring AI、DeepSeek（OpenAI 兼容接口） |
| 沙箱 | docker-java，用户命令在独立容器中执行 |
| 集成 | 阿里云 OSS、GitHub App、SMTP、FCM |
| 前端 | React 19、TypeScript、Vite、Ant Design 6（独立仓库） |

## 目录结构

```text
src/main/java/qg/qgent/     业务代码，按领域分包（见下表）
src/main/resources/         配置、建表脚本、日志与登录公钥
src/test/java/              单元测试（外部集成测试需显式开启）
sandbox-images/dev-tools/   Agent 命令沙箱镜像
Dockerfile                  后端运行镜像
docker-compose.yml          本地依赖、生产部署与可选 profile
.env.example                环境变量模板
```

| 包 | 职责 |
| --- | --- |
| `auth` / `user` | 邮箱验证码注册、RSA + JWT 登录、资料与头像 |
| `team` / `project` | 团队与邀请、项目成员与权限、项目目录 |
| `agent` | 项目内 Agent：默认 Agent、自定义 Agent 申请与群聊审批 |
| `chat` | 群聊、消息、提及、已读、群内历史检索 |
| `harness` | Agent 运行、上下文装配、工具循环、子 Agent |
| `workspace` / `sandbox` | Workspace 与仓库 worktree、容器命令执行与编码工具 |
| `skill` / `memory` | 项目 Skill；项目、群、Agent 三层记忆的后台提炼与群聊审批 |
| `attachment` / `github` | 附件上传预览、GitHub App 与仓库工具 |
| `notification` / `realtime` | 站内通知与推送设备、SSE / WebSocket 事件流 |
| `common` | 统一响应与异常、存储等基础能力 |

## 本地开发

前置：JDK 21、Maven 3.9+、Docker（含 Compose v2）；前端另需 Node.js。

### 1. 填写 .env

```sh
cp .env.example .env
```

Windows PowerShell 使用 `Copy-Item .env.example .env`。至少替换：

- `DB_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD`
- `DEEPSEEK_API_KEY`：默认调用 `deepseek-flash`
- `JWT_SECRET`
- `SMTP_USERNAME`、`SMTP_AUTH_CODE`：注册、邀请与找回密码依赖邮件

OSS、GitHub App、FCM、Cloudflare 等可选项留空即可。后端启动时从仓库根目录读取 `.env`（见 `application.yaml` 的 `spring.config.import`），Compose 读取同一份文件。

### 2. 启动 MySQL 与 Redis

```sh
docker compose up -d --wait
```

默认只启动这两个依赖，端口仅绑定 `127.0.0.1` 的 `13306` 与 `16379`。首次初始化数据卷时执行 `src/main/resources/db/qgents_schema.sql`；复用已有 `mysql_data` 卷不会重新建表（需要重建时先 `docker compose down -v`）。

### 3. 准备登录密钥与沙箱镜像

登录密码由前端用 RSA 公钥加密、后端用私钥解密，仓库只提交了公钥：

- 将配对私钥放到 `RSA_PRIVATE_KEY_PATH`（默认 `src/main/resources/rsa/private_key.pem`）
- 自行生成密钥时，同步更新 `RSA_KEY_ID` 与前端 `.env` 的 `VITE_RSA_KEY_ID`、`VITE_RSA_PUBLIC_KEY`

Agent 执行命令需要沙箱镜像：

```sh
docker compose --profile images build
```

Linux / macOS 需把 `.env` 中 `SANDBOX_DOCKER_HOST` 改为 `unix:///var/run/docker.sock`，并让 `PROJECT_DATA_ROOT` 与 `PROJECT_DATA_DOCKER_HOST_ROOT` 指向同一个宿主机目录；Windows 使用默认的 named pipe，路径用正斜杠，磁盘不存在时改成实际目录（`SANDBOX_WORKSPACE_METADATA_ROOT` 同理）。

### 4. 启动后端

```sh
mvn spring-boot:run
```

| 入口 | 地址 |
| --- | --- |
| API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| 运维端点 | http://127.0.0.1:8081/actuator/health |

Docker 不可用时后端仍能启动，只有需要沙箱的工具会失败；聊天、附件与 GitHub 只读工具不依赖沙箱。

### 5. 启动前端

```sh
cd ../qgents-fronted
cp .env.example .env
npm ci
npm run dev
```

前端 `.env` 使用 `VITE_USE_MOCK=false`、`VITE_API_BASE_URL=/api`、`API_PROXY_TARGET=http://localhost:8080`；Vite 会把 `/api` 转发并重写为 `/api/v1`。页面入口 http://localhost:5173，没有后端时可用 `VITE_USE_MOCK=true` 查看界面。

## 配置索引

| 要调整的内容 | 文件 |
| --- | --- |
| 连接地址、密钥路径、可选服务开关 | `.env.example`，复制为 `.env` |
| Agent 轮次与并发、沙箱资源与超时、附件上限 | `src/main/resources/application.yaml` |
| 服务编排、端口、卷与 profile | `docker-compose.yml` |
| 日志文件、滚动与保留 | `src/main/resources/logback-spring.xml` |
| 后端镜像、沙箱镜像 | `Dockerfile`、`sandbox-images/dev-tools/README.md` |
| 数据库表结构 | `src/main/resources/db/qgents_schema.sql` |

## 测试

```sh
mvn test              # 单元测试，不依赖外部服务
mvn clean package     # 编译、测试并打包
```

需要真实 MySQL、Redis、RSA 私钥与模型配置的端到端测试默认跳过，用 `QGENTS_INTEGRATION_TESTS=true` 开启（同样读取 `.env`）。

## 部署

生产部署由 Compose 的 `production` profile 启动 API 与前端，`images` profile 单独构建沙箱镜像：

```sh
docker compose --profile images build
docker compose --profile production up -d --build
```

部署前准备：

- `QGENTS_DATA_ROOT`：宿主机绝对数据目录，默认 `/srv/qgents`，预先创建 `projects`、`workspace-metadata`、`github-app` 并允许 UID/GID `10001` 写入
- `DOCKER_GID`：`stat -c '%g' /var/run/docker.sock` 的实际组 ID
- `RSA_PRIVATE_KEY_FILE`：宿主机上已有的登录私钥文件
- `FRONTEND_DIRECTORY`：前端仓库目录，默认 `../qgents-fronted`
- `FRONTEND_RSA_PUBLIC_KEY`：前端构建期写入，修改后需重建 Web

Web 默认发布到宿主机 80 端口并反代 `/api` 与 WebSocket；API 仅发布到 `127.0.0.1:8080`。可选 profile：`proxy` 启动 Mihomo，`tunnel` 启动 Cloudflare Tunnel（需 `CLOUDFLARE_TUNNEL_TOKEN`）。

## 协作

- 开发规范与提交约定见 `AGENTS.md`
- 沙箱镜像的构建与内置工具见 `sandbox-images/dev-tools/README.md`
- 项目处于 MVP 阶段：不兼容旧实现，接口与表结构随实现调整
