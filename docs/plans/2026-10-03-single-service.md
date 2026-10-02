# 单服务 Sandbox 合并实施计划

**Goal:** 将主后端与 Sandbox Worker 合并为一个 Spring Boot 应用，工具通过进程内调用执行，调整目录、数据库和部署配置。

**Architecture:** 根目录使用标准 Maven `src/main`、`src/test`。现有业务领域包保留；Sandbox 按 `config`、`runtime`、`service`、`dto`、`execution`、`filesystem` 分包。文件工具同步执行，命令使用独立线程池和完成通知，保留执行句柄、输出游标、取消及 Docker 容器隔离；删除内部 HTTP 协议和独立 Worker 部署。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、MySQL、docker-java。

用户已批准设计并要求在当前分支直接执行与提交，无需再询问执行方式。

## 架构决策

采用单进程、按领域分包的应用：以直接方法调用替代两个服务之间的 HTTP，统一数据源和部署。用户代码仍在 Docker 容器中执行，命令保持独立线程池。代价是聊天与执行模块共用 JVM、发布周期和 Docker 管理权限；现阶段优先降低调用与维护成本，不保留远程/本地双模式，也不引入未来节点调度抽象。

## 1. 保存工作区基线并整理目录

- 保存当前 HEAD、索引和已有未提交文件快照，用于只提交本次变更。
- 将 `qgents-api/src` 移到根目录 `src`；迁入 `qgents-sandbox-worker/src` 的有效实现和测试。
- 合并根 `pom.xml` 依赖和插件，删除两个子模块 POM 与 Worker 启动类。
- 明确包映射，避免同名 Spring Bean、配置和 Mapper 冲突。

## 2. 替换 HTTP 调用

- `SandboxSessionManager` 直接调用容器与 Workspace 管理服务。
- Workspace 文件、检索和写入适配器直接调用工具执行服务，沿用现有工具参数和返回内容。
- 删除 `sandbox/client`、传输 DTO、内部 Controller、内部 Token Filter 和 HTTP 配置及其专属测试。
- 保留代码环境按需创建、根运行共享环境、持久 Workspace 和实际执行关联。

## 3. 简化执行等待与记录

- 文件工具在调用线程执行；长命令在线程池执行。
- 使用进程内完成通知等待命令，超时返回执行句柄；查询输出返回执行状态与 stdout/stderr 窗口。
- 保留执行失败与非零退出码的区分、取消和运行结束回收。
- 同一数据源保存必要执行记录，去除重复 Worker 回执记录与远端轮询。
- 测试短工具同步执行、命令短等待/结束通知、取消、输出及并行行为。

## 4. 合并配置、数据库与部署

- 根 `src/main/resources/application.yaml` 使用一个数据源并合并 Sandbox 配置。
- Sandbox 执行建表语句并入主数据库；删除独立 Worker 数据库和 HTTP 契约。
- `Dockerfile.production` 构建单一应用，保留非 root 用户、Docker Socket 组权限和 Workspace 挂载。
- `docker-compose.production.yml` 删除 Worker 服务，Sandbox 配置与卷移到 API。
- 更新 `.env.example`、构建/启动脚本及相关文档中的路径和启动方式。

## 5. 验证

- 执行 `./mvnw.cmd clean test -q`，修正迁移后编译和相关测试。
- 执行 `./mvnw.cmd package -DskipTests -q`，确认单一可执行 JAR。
- 检查 Spring Bean/Mapper 扫描、单数据源 schema、部署 YAML 和旧 HTTP/模块引用。
- 无真实 Docker/MySQL/模型环境时明确记录联调限制，不声称已验证真实运行。

## 6. 更新协作约定并提交

- 更新 `AGENTS.md` 与 `docs/target.md`，替换独立 Worker 要求并记录实际完成状态。
- 对照基线只暂存本次差异；目录迁移保留既有未提交内容为未提交状态。
- 在临时目录验证暂存树可编译/测试后，提交可独立理解且可回滚的合并实现，不推送。

## 验证结果

- 目录、进程内调用、执行通知、单数据源和部署配置迁移已完成。
- `mvnw.cmd clean package -q`：389 项通过、3 项跳过，单一可执行 JAR 打包成功。
- 独立提交树 `mvnw.cmd clean test -q`：381 项通过、3 项跳过，未依赖其他已有未提交改动。
- `docker compose -f docker-compose.production.yml --env-file .env.example config --quiet`：注入仅用于配置检查的非真实模型/隧道占位值后通过。
- JAR 的入口为 `qg.qgent.QgentsApplication`，无旧 Worker 类，不包含 RSA 私钥。
- 真实 Docker、MySQL 和模型联调未执行。
