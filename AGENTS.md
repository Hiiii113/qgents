# 工作规则

- 最小改动：只解决当前问题，不写推测性功能；只改必须改的地方，自己产生的问题自己清理，不顺手扩大范围。
- 不确定就说出来：列出具体会怎么出错、概率多大、要多写多少代码，等我同意再动手。
- 成功标准先定清楚，验证通过前持续迭代。

## 代码

- 外部数据（用户输入、外部 API、网络）在入口校验一次；往里传已知类型的值，内部不再防御性检查。
- 信任内部代码和框架保证；失败就抛出来，不宽泛 catch，不靠静默默认值。
- 主函数只写正常流程，校验和细节放进下一层小函数。
- 抽函数的前提：会复用、包住了确实复杂的逻辑，或它代表一个能起名字的概念。
- 注释只写不明显的约束和反直觉的行为。
- 按仓库现有流程走，不引入仓库没在用的工具。

## 测试

- 测真实实现，尽量少 mock；不把业务逻辑抄一份进测试。
- 测核心业务逻辑、关键边界条件、历史 Bug 和高风险行为；不为简单代码写测试。
- 测试失败就修实现：不删用例、不削弱断言、不跳过、不改正确的业务行为来过关。
- 已失效或重复的用例可以删，先确认没有丢掉独立的行为验证。

## 项目

- 前端仓库在 `../qgents-fronted`。
- 项目在 MVP 阶段：不兼容旧实现，不做多余的安全拦截；遇到违背这些规则的旧代码可以直接指出并重构精简。
- 未实施但已认可的事项记入 `docs/pending.md`，区分已确定与待决定；实施后同步更新。
- 提交按标准格式，一次提交只做一件事，可以拆成多次。

## 模块地图

`src/main/java/qg/qgent/` 下按业务分包，每包自带 `controller / service / mapper / entity / dto`，缺哪层说明这层不需要。

- `agent/` 自定义 Agent 的申请与审批，`tool/AgentApplicationTools` 是审批工具。
- `harness/` 自研工具循环与运行生命周期：`runtime/` 是主循环与并行工具回调，`briefing/` 管上下文组装、注入顺序与字符预算。
- `memory/` 三种记忆：项目 `memory.md`、群 `groups/<groupId>/memory.md`、Agent 私有 `agent-memory/<agentId>/<groupId>.md`，统一由 `MemoryDocument` 表示；`ProjectMemoryDiscovery` 是后台发现。
- `sandbox/` Docker 沙箱生命周期、文件系统与命令执行，`tool/CodingTools` 是给模型的编码工具。
- `workspace/` 项目仓库的 worktree、写租约与操作锁。
- `chat/` 群聊、提及、已读与跨群检索，`CrossGroupAccessService` 决定一次跨群读取是否被允许。
- `project/ team/ user/ auth/` 组织与人，`ProjectAccess` 是项目内的权限判定。
- `skill/` 项目 Skill；`attachment/` 附件与对象存储；`github/` GitHub App、仓库导入与 PR/Issue 读取。
- `realtime/` SSE 与 WebSocket 事件出口；`notification/` 站内通知与 FCM 推送。
- `common/` 跨包基础设施（`web/ApiException`、数据库连接监控、存储）。

表结构在 `src/main/resources/db/qgents_schema.sql`，配置入口只有 `src/main/resources/application.yaml`。测试镜像同一套包名，放在 `src/test/java/qg/qgent/<包>/`。

## 并行编排

- 加载 `herdr-core` skill 时用 `hc` 编排：可以派 subagent，也可以并行开发。
- 并行开发用 `git worktree` 隔离改动，按依赖顺序调度，不让两个 agent 改同一份工作区。
- agent 跑完就收尾：结束终端、关掉分屏，不留后台进程。

## Agent skills

- Issue 与 spec 存为 GitHub issue（`Hiiii113/qgents`），统一用 `gh` 操作 → `docs/agents/issue-tracker.md`
- 五个默认 triage 标签 → `docs/agents/triage-labels.md`
- 领域文档（`GLOSSARY.md`、`docs/adr/`）→ `docs/agents/domain.md`
