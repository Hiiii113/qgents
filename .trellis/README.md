# Qgents 的 Trellis

已接入 Trellis 0.6.17（Codex），仅用于本仓库开发协作。根目录 AGENTS.md 和 workflow 开头的项目适配优先。

## 使用

新会话可直接描述需求，Agent 从 AGENTS.md 发现 Trellis；需要显式启动时输入 `$trellis-start` 或“使用 Trellis 继续工作”。实施过程按需读取规范、创建任务、验证并提交，收尾使用 `$trellis-finish-work` 或“记录本次工作”。重开会话后加载新安装的项目 Skill。

PowerShell 手动查看：

```powershell
python .trellis/scripts/get_context.py
python .trellis/scripts/get_context.py --mode phase
python .trellis/scripts/get_context.py --mode packages
python .trellis/scripts/task.py list
```

Hook 已提供在 `.codex/hooks.json`。启用全局 `features.hooks` 后仍需要 Codex 宿主首次审核；本机 CLI 可用 `/hooks` 检查。未批准或宿主不支持时通过 AGENTS.md、启动 Skill 和脚本加载，不能声称 Hook 已自动生效。

## 与 Matt 共存

Matt Skill 保留在用户级安装位置。项目仅接入 Trellis 生命周期；Matt 方法按需使用。保留 GLOSSARY.md 的产品术语及 docs/specs/qgent-harness-refactor.md 的需求与验收内容；已删除其中失效的 Matt tracker 初始化说明。

## 维护

`trellis upgrade` 更新 CLI，项目中 `trellis update --dry-run` 先预览模板变更。workflow、部分 Skill 和后端规范含项目适配，更新时保留或合并本地修改，不使用 force 覆盖。日志/归档自动提交已关闭，按项目提交约定由 Agent 显式提交。
