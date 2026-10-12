# 接入 Trellis 与初始化 Qgents 规范

## 已授权范围
用户要求安装使用 Trellis，检查 Matt 项目痕迹，冲突时仅清理项目痕迹；已确认沿用 develop 分支。

## 验收
- [x] 安装官方 CLI 0.6.17 并生成 Codex 项目配置。
- [x] 保留原 AGENTS.md 和全部全局 Matt Skill。
- [x] 填写后端规范，引用真实代码、产品愿景和术语表。
- [x] 用项目适配处理重复审批、提交和默认委派冲突。
- [x] 保留有用的 Matt 生成文档，删除失效的 tracker 初始化说明。
- [x] 检查配置、脚本、上下文、任务生命周期与文档链接。

## 边界
不修改业务代码、数据库、前端仓库或产品内 Agent。Hook 首次宿主审核与自动触发需要用户在新会话确认，脚本验证不代替自动触发验证。

## 实际验证

- 31 个 Python 文件通过语法与编译检查，4 个 JSON、4 个 TOML 通过解析；13 个 Markdown 链接及规范中的源码路径有效。
- task.py validate、start 与 get_context 的上下文/阶段/包索引运行成功。
- UserPromptSubmit 和 SessionStart Hook 直接运行，返回合法事件 JSON 和项目上下文；未声称宿主已自动触发。
- trellis update --dry-run 正确保留项目资料并标识本地适配；git diff --check 通过。
- 本轮仅接入开发工具和文档，未改业务代码，未运行 Maven 或真实服务联调。
