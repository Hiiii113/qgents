# 目录结构

单个 Spring Boot 服务，Java 21、Spring AI、自有 Agent loop。生产代码在 `src/main/java/qg/qgent/`，测试在 `src/test/java/qg/qgent/`，按领域对应。

- 按业务领域分包，内部按需分 controller/service/dto/entity/mapper/config/tool，不建根目录统一分层包。
- `harness` 负责 loop、运行生命周期、上下文、委派和工具组装；业务工具放各自领域。
- `workspace` 管持久资源、访问和租约；`sandbox` 管进程内 Docker、文件与命令执行。用户命令仍在 Docker 中执行。
- 前端是独立仓库 `D:/code/qgents-fronted`，接口修改核对实际调用。

实际入口：`src/main/java/qg/qgent/QgentsApplication.java`；工具示例：`src/main/java/qg/qgent/workspace/tool/WorkspaceTools.java`、`src/main/java/qg/qgent/github/tool/GitHubTools.java`。
