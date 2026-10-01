# Agent 开发沙箱镜像

内置 Git、JDK、Maven、Node、Python 和 ripgrep，完整工作区挂载到 `/workspace`。
Agent 通过参数数组命令选择仓库、创建分支和提交；GitHub 认证由 Agent 可选工具提供临时环境。
镜像不内置 token，也不依赖共享 Git Store 或宿主机 `.git` 标记。

```powershell
docker build -t qgents/sandbox-dev-tools:0.2.0 sandbox-images/dev-tools
```
