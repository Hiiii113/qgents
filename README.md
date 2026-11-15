# Qgents

在项目群里 @ 一个 Agent，它就接着这件事往下做：读群聊上下文、项目知识和你丢进群里的附件，在 Docker 沙箱里改代码、跑命令，把分支推回 GitHub。

Agent 是群里的成员，不是对话框。它有项目内的独立 ID、独立记忆和权限，只在被 @ 的群里、被 @ 的那条消息上运行，跑偏了可以按消息停掉。产出是一个真的分支，不是一段看起来对的文本。

后端是单个 Java 21 / Spring Boot 应用，同时承载群聊、Agent 运行和沙箱管理，依赖 MySQL 与 Redis。前端在相邻仓库 `qgents-fronted`（React 19）。

## 为什么是群聊

- 对话式 AI 编码助手要人来回搬上下文。这里上下文属于项目自己：共享的 `memory.md`、群记忆、Agent 私有记忆、附件、群历史，Agent 直接读。
- 把机器人当账号挂进 IM 的做法，一行消息就能触发模型。这里 Agent 是项目成员，有身份、记忆和权限；只有显式 @ 群内已启用的 Agent 才启动运行，普通聊天不调用模型。

层级固定为团队 → 项目 → 群聊。新建项目会生成该项目自己的默认 Agent（名字叫 Qgent）和默认群；新群只在有人选择时才把 Agent 拉进来。

## 功能

- 项目群聊：团队 → 项目 → 多个群，支持成员、提及、回复、已读与置顶。只有 @ 了群内 Agent 的消息才启动运行。
- Agent 运行：自研工具循环，默认最多 30 轮，单步最多 4 个工具并行；可以后台派生子 Agent（默认上限 8 个），运行中可以取消。
- Docker 沙箱：用户命令在临时容器里执行，默认 2 核 / 4 GiB，空闲 30 分钟后回收，最长闲置 2 小时；单条命令默认 15 分钟超时、上限 1 小时。
- 三层记忆：项目共享的 `memory.md`、群记忆 `groups/<groupId>/memory.md`、Agent 私有记忆 `agent-memory/<agentId>/<groupId>.md`，都存在项目数据目录里，不是数据库表单。
- 项目知识：共享记忆、项目 Skill、跨群历史检索、图片与文本附件（本地磁盘或阿里云 OSS 直传）。
- GitHub App（可选）：团队安装、项目管理员导入仓库；Agent 能读 PR 和 Issue、拉代码、推分支，不会自动创建或合并 PR。
- 实时与通知：项目事件走 SSE（可用 `Last-Event-ID` 续传）和 WebSocket，站内通知可选接 FCM 推到手机。

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

## 快速开始

需要 JDK 21、Maven 3.9+、Docker（含 Compose v2）。前端另需 Node.js。

### 1. 填写 .env

```sh
cp .env.example .env
```

Windows PowerShell 用 `Copy-Item .env.example .env`。至少改这几项：

- `DB_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD`
- `DEEPSEEK_API_KEY`：默认调用 `deepseek-flash`
- `JWT_SECRET`
- `SMTP_USERNAME`、`SMTP_AUTH_CODE`：注册、邀请、找回密码都要发邮件

OSS、GitHub App、FCM、Cloudflare 这些可选项留空就行。后端启动时从仓库根目录读 `.env`（见 `application.yaml` 的 `spring.config.import`），Compose 读的是同一份。

### 2. 启动 MySQL 与 Redis

```sh
docker compose up -d --wait
```

默认只起这两个依赖，端口只绑在 `127.0.0.1` 的 `13306` 和 `16379`。数据卷第一次初始化时会执行 `src/main/resources/db/qgents_schema.sql`；复用已有的 `mysql_data` 卷不会重新建表。加了新表之后要么手动补，要么 `docker compose down -v` 重建。

### 3. 准备登录密钥与沙箱镜像

登录密码由前端用 RSA 公钥加密、后端用私钥解密，仓库里只有公钥。把配对的私钥放到 `RSA_PRIVATE_KEY_PATH`（默认 `src/main/resources/rsa/private_key.pem`）；自己生成密钥时，同时更新 `RSA_KEY_ID` 和前端 `.env` 的 `VITE_RSA_KEY_ID`、`VITE_RSA_PUBLIC_KEY`。

Agent 执行命令需要沙箱镜像：

```sh
docker compose --profile images build
```

Linux 和 macOS 要把 `.env` 里的 `SANDBOX_DOCKER_HOST` 改成 `unix:///var/run/docker.sock`，并让 `PROJECT_DATA_ROOT` 与 `PROJECT_DATA_DOCKER_HOST_ROOT` 指向同一个宿主机目录。Windows 用默认的 named pipe，路径写正斜杠；磁盘不存在就改成实际目录（`SANDBOX_WORKSPACE_METADATA_ROOT` 同理）。

### 4. 启动后端

```sh
mvn spring-boot:run
```

| 入口 | 地址 |
| --- | --- |
| API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| 运维端点 | http://127.0.0.1:8081/actuator/health |

Docker 不可用时后端照样能启动，只有需要沙箱的工具会失败；聊天、附件和 GitHub 只读工具不依赖沙箱。

### 5. 启动前端

```sh
cd ../qgents-fronted
cp .env.example .env
npm ci
npm run dev
```

前端 `.env` 用 `VITE_USE_MOCK=false`、`VITE_API_BASE_URL=/api`、`API_PROXY_TARGET=http://localhost:8080`，Vite 会把 `/api` 转发并重写成 `/api/v1`。页面在 http://localhost:5173；没有后端时把 `VITE_USE_MOCK` 设成 `true` 可以直接看界面。

## 配置索引

| 要调的东西 | 文件 |
| --- | --- |
| 连接地址、密钥路径、可选服务开关 | `.env.example`，复制成 `.env` |
| Agent 轮次与并发、沙箱资源与超时、附件上限 | `src/main/resources/application.yaml` |
| 服务编排、端口、卷与 profile | `docker-compose.yml` |
| 日志文件、滚动与保留 | `src/main/resources/logback-spring.xml` |
| 后端镜像、沙箱镜像 | `Dockerfile`、`sandbox-images/dev-tools/README.md` |
| 数据库表结构 | `src/main/resources/db/qgents_schema.sql` |

## 目录结构

```text
src/main/java/qg/qgent/     业务代码，按领域分包（见下表）
src/main/resources/         配置、建表脚本、日志与登录公钥
src/test/java/              单元测试（外部集成测试要显式开启）
sandbox-images/dev-tools/   Agent 命令沙箱镜像
Dockerfile                  后端运行镜像
docker-compose.yml          本地依赖、生产部署与可选 profile
.env.example                环境变量模板
```

| 包 | 职责 |
| --- | --- |
| `auth` / `user` | 邮箱验证码注册、RSA + JWT 登录、资料与头像 |
| `team` / `project` | 团队与邀请、项目成员与权限、项目目录 |
| `agent` | 项目内 Agent：默认 Agent、自定义 Agent 的申请与群内审批 |
| `chat` | 群聊、消息、提及、已读、群内历史检索 |
| `harness` | Agent 运行、上下文装配、工具循环、子 Agent |
| `workspace` / `sandbox` | Workspace 与仓库 worktree、容器命令执行与编码工具 |
| `skill` / `memory` | 项目 Skill；项目、群、Agent 三层记忆 |
| `attachment` / `github` | 附件上传预览、GitHub App 与仓库工具 |
| `notification` / `realtime` | 站内通知与推送设备、SSE / WebSocket 事件流 |
| `common` | 统一响应与异常、存储等基础能力 |

## 测试

```sh
mvn test              # 单元测试，不依赖外部服务
mvn clean package     # 编译、测试并打包
```

需要真实 MySQL、Redis、RSA 私钥和模型配置的端到端测试默认跳过，用 `QGENTS_INTEGRATION_TESTS=true` 开启（同样读 `.env`）。

## 部署

生产由 Compose 的 `production` profile 起 API 和前端，`images` profile 单独构建沙箱镜像：

```sh
docker compose --profile images build
docker compose --profile production up -d --build
```

部署前准备好这几项：

- `QGENTS_DATA_ROOT`：宿主机上的绝对数据目录，默认 `/srv/qgents`，先建好 `projects`、`workspace-metadata`、`github-app`，并允许 UID/GID `10001` 写入
- `DOCKER_GID`：`stat -c '%g' /var/run/docker.sock` 得到实际组 ID
- `RSA_PRIVATE_KEY_FILE`：宿主机上已有的登录私钥文件
- `FRONTEND_DIRECTORY`：前端仓库目录，默认 `../qgents-fronted`
- `FRONTEND_RSA_PUBLIC_KEY`：前端构建期写死，改了要重建 Web 镜像

Web 默认发到宿主机 80 端口，反代 `/api` 和 WebSocket；API 只发到 `127.0.0.1:8080`。可选 profile：`proxy` 起 Mihomo，`tunnel` 起 Cloudflare Tunnel（要填 `CLOUDFLARE_TUNNEL_TOKEN`）。

## 文档

- `sandbox-images/dev-tools/README.md`：沙箱镜像的构建与内置工具

## 安全

仓库里没有私钥和 `.env`，只提交了可以公开的登录公钥。本地跑起来时自己生成密钥，`.env` 不进版本库。发现安全问题请开 issue，或直接联系维护者，不要在公开 issue 里贴利用细节。

## 参与开发

项目还在 MVP 阶段：不兼容旧实现，接口和表结构会随实现调整。提交前跑一遍 `mvn test`；改动大一点的话，先开 issue 说清楚要解决什么，比直接丢一个 PR 省事。

## License

[MIT](LICENSE)
