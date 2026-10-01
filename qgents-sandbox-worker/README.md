# Sandbox Worker

独立部署的工作区与沙箱执行服务，协议见 `../contracts/sandbox-worker-openapi.yaml`。

- `PUT /internal/v1/workspaces/{workspaceId}` 只需 projectId，创建空目录。同一工作区重复准备保留所有文件。
- 完整工作区以可写 `/workspace` 挂载到沙箱；销毁沙箱保留目录，Task 继续执行时复用目录。
- Worker 不同步 Git Store、不登记仓库、不创建分支或提交；Agent 在沙箱中调用 Git。
- 文件工具保留读取、列表、搜索、写入、补丁、目录创建及末尾换行能力，所有路径相对工作区根目录。
- `development.run` 保留固定开发命令；`command.run` 接收 `command` 参数数组和可选 `directory`，返回 stdout/stderr 与实际退出码。
- Tool 请求的 `environment` 只用于本次进程，不写入执行参数或数据库。

配置 `SANDBOX_BACKEND_SERVICE_TOKEN`、Worker 数据库、Docker Socket、工作区根目录与镜像白名单。
Docker 绑定源使用 `SANDBOX_WORKSPACE_DOCKER_HOST_ROOT`；Worker 读取路径使用 `SANDBOX_WORKSPACE_LOCAL_ROOT`，两者必须指向同一存储。
沙箱镜像 `qgents/sandbox-dev-tools:0.2.0` 含 Git、JDK、Maven、Node、Python 与 ripgrep。
GitHub 工具需要沙箱可访问 GitHub，按部署网络设置 `SANDBOX_NETWORK_POLICY`（NONE 不支持远端操作）。

```powershell
.\mvnw.cmd -pl qgents-sandbox-worker test
docker build -t qgents/sandbox-dev-tools:0.2.0 sandbox-images/dev-tools
docker compose -f docker-compose.production.yml up -d sandbox-worker
```
