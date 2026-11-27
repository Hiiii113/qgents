# 个人 MCP 连接由成员管理

项目管理员发布个人 MCP 选项，统一管理服务地址和鉴权方式。成员为自己建立个人连接，并选择可以使用它的 Agent。同一个项目 Agent 会在同一个群为多位成员运行，由连接所有者分配 Agent，可以保留各自对外部服务的权限选择。后端在调用时用根运行的发起人匹配连接所有者，并检查该 Agent 的分配仍然有效。[运行身份](../../src/main/java/qg/qgent/harness/service/AgentRunService.java)
