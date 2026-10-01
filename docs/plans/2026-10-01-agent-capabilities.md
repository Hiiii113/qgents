# 项目群聊与 Agent 附加能力重构计划

**Goal:** 每个项目只有一个默认群聊；Task 只持有固定工作区；仓库、分支和 GitHub 操作由 Agent 工具决定。

**Architecture:** 沿用现有 AgentLoop、文件工具和沙箱 Worker。工作区改为普通持久目录，不再预置 GitHub 仓库或 linked worktree。Agent 可选配置 GitHub 凭证引用，通过 GitHub HTTP 工具与沙箱 Git 命令完成开发和 PR 操作。

**Tech Stack:** Java 21、Spring Boot、Spring AI ToolCallback、MyBatis、MySQL、Docker、Git。

## 决策与边界

- 选择 Tool 实现，避免同时引入 MCP 客户端、插件市场与全新 Skill 执行框架；已有 Skill 激活继续保留。
- 普通消息不触发模型；@Agent 启动 loop。模型按需创建 Task，普通回答不强制 Task。
- GitHub 是 Agent 的附加能力，凭据由部署配置提供，Agent 保存连接引用；未配置的 Agent 仍能问答和使用普通工作区。
- Agent 根据用户目标决定仓库、分支、commit、push、PR 操作，系统不执行隐式提交或固定交付阶段。
- clone 不指定 base 时使用远端默认分支；Agent 可传 base。clone 本身不创建任务分支。
- 每个 Task 的工作区持久复用；仓库是 Agent 在目录里拉取的文件，不是项目/Task 数据库绑定。
- 不兼容旧需求群、仓库绑定、MR 镜像与 GitHub App/OAuth/Webhook 业务接口；新建表脚本为准，不自动迁移或删除现有数据库。

## 实施顺序

### 1. 项目唯一群聊

- 重命名 RequirementGroupEntity/Mapper 为 ChatGroupEntity/Mapper，关联字段统一 groupId/group_id。
- 自动创建项目默认群；唯一索引约束 project_id；成员直接使用项目成员。
- 删除需求群创建、归档、独立成员增删/退出、群仓库绑定接口、DTO、Mapper 和表。
- 保留消息、搜索、未读、置顶、附件、@Agent 和实时推送；更新广播范围与测试。
- 验证：`./mvnw.cmd -pl qgents-api test`；重点覆盖项目创建群、访问、成员和置顶。

### 2. 固定空工作区与通用命令

- Task API 不再接收仓库 ID、分支映射；创建固定空工作区。
- Worker provision 只准备目录，删除 Git Store、受控 Git 提交/推送接口、仓库元数据和 .git 隔离挂载。
- 挂载完整工作区，文件工具使用工作区相对路径。
- 添加参数数组命令工具，可指定工作区内目录，返回真实 exitCode/stdout/stderr；保留现有固定开发命令。
- 沙箱镜像安装 Git，GitHub 认证仅随单次 Git 命令注入，不写入仓库 URL、数据库或模型输入。
- 验证：空目录 provision/复用、文件操作、命令目录越界、失败退出码、凭证不持久化、取消。

### 3. Agent GitHub Tool 能力

- Agent 配置 githubCredentialRef，连接值由部署环境提供；创建/修改/查询均支持该字段。
- GitHub 工具按当前 Agent 配置开放，提供仓库列表、GitHub HTTP 请求、clone 和 push。
- 本地 Git 分支、diff、commit 等通过通用命令；PR 通过 GitHub HTTP 工具。工具明确描述外部写操作的效果。
- 删除 AgentChatService 中项目仓库查询与 MergeRequestService 依赖，提示词说明按用户任务自主选择工具。
- 测试普通问答不创建 Task、工具按 Agent 开放、默认 base、固定工作区复用、HTTP 失败与 Git 失败。

### 4. 移除系统 GitHub 业务

- 删除 github/mergerequest 旧业务包、项目仓库 DTO/Mapper/Entity 和绑定流程。
- 清理 Team 动态、团队删除、鉴权例外、幂等范围、配置、环境样例、数据库及旧测试的引用。
- 项目创建仅创建项目、成员和群，不调用外部 GitHub。
- 更新 README、Worker 文档、OpenAPI 契约与 AGENTS.md；确认无遗留旧机制。

### 5. 验证与提交

- 执行 `./mvnw.cmd test` 与 `./mvnw.cmd package -DskipTests`，检查 `git diff --check`。
- 使用本地 Git 仓库与模拟 HTTP 验证，不创建真实远端 PR、不推送当前仓库。
- 用户已授权 commit；按独立可编译的功能拆提交。群聊单独提交，其余不可拆出的工作区/能力/移除流程一起提交。
- 每个提交包含 Conventional Commits 中文标题、实现说明、实际验证命令与结果。

## 实施结果

- 项目唯一默认群、固定空工作区、Agent 可选 GitHub Tool 和旧系统 GitHub 业务清理均已完成。
- 保留普通聊天、@Agent、文件工具、Task 继续执行、subagent、技能、记忆及实时消息。
- GitHub 能力支持直接 REST 请求、默认基线 clone、push 和带认证的 Git 命令；默认 Agent 可由 Team Owner 配置能力。
- `./mvnw.cmd clean test -q`：API 326 项、Worker 84 项，0 失败、0 错误，7 项按外部服务或平台条件跳过。
- `./mvnw.cmd package -DskipTests -q`：两个模块打包成功。`git diff --check`、YAML 解析和 SQL 外键表引用检查通过。
- 群聊提交单独检出后执行 `./mvnw.cmd -pl qgents-api compile -DskipTests -q`，编译成功。
- 未修改现有运行数据库、未做真实 GitHub/Docker 联调；部署需新版表结构、重建沙箱镜像，并按需配置 Agent GitHub 凭证引用。
