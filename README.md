# Qgents

IM 聊天、可选 GitHub App 集成和云端 Agent Harness。普通消息仅用于聊天，`@Agent` 启动自有工具 loop；首次需要代码环境时准备 Docker Sandbox，运行结束释放沙箱并保留 Workspace。

后端与 Sandbox 为单个 Java 21 Spring Boot 应用，依赖 MySQL 和 Redis。前端位于相邻的 `../qgents-fronted` 仓库。

## 本地开发

1. 将 `.env.example` 复制为 `.env`，填写数据库、Redis、`DEEPSEEK_API_KEY`、SMTP 和 JWT 配置。Spring 自动读取 `.env`。
2. 启动 Docker Desktop（Linux containers），执行 `docker compose up -d --wait`，启动 MySQL 8.4 和 Redis。应用数据库用户使用 `qgents` 等非 root 用户。首次创建 MySQL 数据卷时自动执行 `src/main/resources/db/qgents_schema.sql`；复用已有数据卷不会重新建表。默认映射到本机 13306 和 16379，分别对应 `MYSQL_HOST_PORT`、`REDIS_HOST_PORT`，本机 API 的 `DB_URL` 与 `REDIS_PORT` 须使用这两个端口。
3. 将已有登录 RSA 私钥放到 `RSA_PRIVATE_KEY_PATH` 指定位置，前端公钥和 keyId 须与其对应。项目公钥位于 `src/main/resources/rsa/public_key.pem`，仅有公钥无法生成对应私钥。
4. Windows Docker Desktop 使用 `SANDBOX_DOCKER_HOST=npipe:////./pipe/docker_engine`；本地运行时两个 Workspace 根目录指向同一实际目录。
5. 执行 `docker build -t qgents/sandbox-dev-tools:0.2.0 sandbox-images/dev-tools`，再运行 `./mvnw.cmd spring-boot:run`。不涉及代码环境的工具无需 Docker。

前端目录执行 `npm ci`、`npm run dev`，`.env` 使用 `VITE_USE_MOCK=false`、`VITE_API_BASE_URL=/api`、`API_PROXY_TARGET=http://localhost:8080`。页面入口为 `http://localhost:5173`。

API 为 `http://localhost:8080/api/v1`，Swagger 为 `http://localhost:8080/swagger-ui/index.html`，管理端口默认仅监听 `127.0.0.1:8081`。

## 配置

- 默认使用 [DeepSeek 官方接口](https://api-docs.deepseek.com/zh-cn/)的 `deepseek-flash`，通过 `DEEPSEEK_API_KEY` 鉴权；使用 `AI_BASE_URL`、`AI_MODEL`、`AI_MAX_TOKENS` 配置地址、模型和输出上限。
- 附件默认保存到 `ATTACHMENT_LOCAL_ROOT`，也支持 OSS；头像直传需要配置 OSS 公共访问地址。
- GitHub App 可选，Agent 的 GitHub 能力默认关闭。配置见 [docs/github-app.md](docs/github-app.md)，导入仓库不自动 clone 或绑定 Workspace。
- `FRONTEND_URL` 为页面入口；独立 API 域名部署时将页面来源加入 `CORS_ALLOWED_ORIGINS`。

## Linux Docker 部署

`Dockerfile` 构建单个 API 服务；`docker-compose.production.yml` 构建相邻前端仓库并启动 MySQL、API、Web、Redis。MySQL 使用独立数据卷并在首次启动时自动建表；API 固定通过 `mysql:3306` 连接，等待数据库完成初始化后启动。生产数据库不发布宿主机端口。

填写 `.env` 必需配置及以下部署参数：

- `RSA_PRIVATE_KEY_FILE`：宿主机现有登录私钥文件。
- `QGENTS_DATA_ROOT`：宿主机绝对数据目录，默认 `/srv/qgents`。
- `DOCKER_GID`：`stat -c '%g' /var/run/docker.sock` 的实际组 ID。
- `FRONTEND_DIRECTORY`：前端仓库目录，默认 `../qgents-fronted`。
- `FRONTEND_RSA_PUBLIC_KEY`：更换密钥时填入对应 PEM 公钥，可用字面量 `\n` 表示换行；修改后重建 Web。

预先创建数据根目录下的 `workspaces`、`workspace-metadata`、`github-app` 目录。工作区和元数据目录需允许 UID/GID `10001` 写入。GitHub App 可选私钥放到 `github-app/private-key.pem`；API 用户需能读取登录及 App 私钥。

```sh
docker compose -f docker-compose.production.yml config --quiet
docker compose -f docker-compose.production.yml --profile images build
docker compose -f docker-compose.production.yml up -d
```

Web 默认端口 80，API 仅发布到宿主机 `127.0.0.1:8080`。Web 将 `/api` 与 WebSocket 转发到 API。容器内 Workspace 路径和 Docker 宿主机挂载路径须对应同一份数据。

可选 `proxy` profile 启动 Mihomo，配置保存在 `mihomo_config` 卷；`tunnel` profile 启动 Cloudflare Tunnel，需填写 `CLOUDFLARE_TUNNEL_TOKEN`，Web 路由指向 `http://web:80`。

## 验证与协作

执行 `./mvnw.cmd clean package -q` 编译、测试和打包。外部集成测试可能跳过，真实容器、数据库、模型和 GitHub 联调需要对应环境。

愿景与实现差距见 [docs/target.md](docs/target.md)，工具说明见 [docs/sandbox-tools.md](docs/sandbox-tools.md)，协作规范见 [AGENTS.md](AGENTS.md)。
