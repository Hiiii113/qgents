# 目录结构

单个 Spring Boot 服务，Java 21、Spring AI、自有 Agent loop。生产代码在 `backend/src/main/java/qg/qgent/`，测试在 `backend/src/test/java/qg/qgent/`，按领域对应。

- `backend/` 为独立 Maven 模块；根 POM 聚合构建并管理依赖及插件版本。根目录执行 `./mvnw.cmd clean package -q`，本地启动使用 `./mvnw.cmd -pl backend spring-boot:run`，应用工作目录保持为仓库根目录。
- `sandbox-images/` 保留独立 Docker 构建上下文，提供用户命令运行环境；根目录 Dockerfile 构建后端应用镜像。
- 按业务领域分包，内部按需分 controller/service/dto/entity/mapper/config/tool，不建根目录统一分层包。
- `harness` 负责 loop、运行生命周期、上下文、委派和工具组装；业务工具放各自领域。
- `workspace` 管持久资源、访问和租约；`sandbox` 管进程内 Docker、文件与命令执行。用户命令仍在 Docker 中执行。
- 前端是独立仓库 `D:/code/qgents-fronted`，接口修改核对实际调用。

实际入口：`backend/src/main/java/qg/qgent/QgentsApplication.java`；工具示例：`backend/src/main/java/qg/qgent/workspace/tool/WorkspaceTools.java`、`backend/src/main/java/qg/qgent/github/tool/GitHubTools.java`。
