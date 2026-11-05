# OpenBot 与 Qgents：聊天记录如何进入 Memory

分析对象：[nightly-labs/openbot](https://github.com/nightly-labs/openbot)，本地克隆位于 `D:\code\openbot`，代码版本 `ffd04b3415a270b6b149df282a3f83abef5e350e`；Qgents 以当前工作树为准，基准提交为 `86d38ecf19799e508f5ff652cb0d1b2c2d87913b`。本报告基于静态代码阅读，未启动应用、调用模型或检查实际用户数据。OpenBot 源码链接固定到上述提交。

## 结论

OpenBot 有两条将聊天信息转为 Agent 长期记忆的主要路径：正常对话中由 Agent 主动调用记忆工具；以及符合条件的个人会话和侧线程在后台触发 memory review，让模型根据聊天和已有记忆提出增、改、删操作。后台审阅不是将所有聊天原文直接存入 Memory，也不覆盖所有频道消息。

Qgents 目前没有后台聊天记忆审阅。普通聊天持久化后不会自动生成 Memory；用户可手工创建，也可以在 `@Qgent` 后由模型决定是否调用 `create_memory`。Qgents 的 Memory 是项目级审核知识，而 OpenBot 的自动审阅主要维护 Agent 自身的记忆，两者的所有权和受众不同。

## OpenBot：后台审阅如何把聊天变成记忆

```text
个人聊天或侧线程完成一轮
  → 符合条件的 turn 计数
  → 达到阈值，或满足空闲/清理等触发条件
  → 读取本会话新增聊天 + 相关已有记忆
  → 独立后台模型审阅，输出 JSON 操作
  → 应用校验和逐项提交 add / update / forget
  → 保存到该 Agent 的 SQLite 记忆
  → 必要时在聊天中显示记忆变化
```

### 1. 触发时机与范围

- 默认每个 Agent 的个人聊天或侧线程累计 **20 个符合条件的已完成 turn** 后审阅；频率可设置为 0、10、20、40，0 表示关闭后台审阅。
- 至少有 3 个新 turn，且最后一轮结束后空闲 15 分钟时，可以提前审阅。清空主聊天时会尝试审阅；关闭已有记录且有新 turn 的侧线程时也会尝试。删除侧线程则取消，不触发审阅。
- 计数的是符合条件的对话 turn，不是 20 条消息：未完成、quiet、自动续接中的阶段和 routine 来源不计入。channel turn 和 routine session 不进入这条后台审阅路径。
- 一次只有一个后台 memory review 运行；资源不足时等待。计数和游标保存在进程内，重启后不保留。

代码：[turn-lifecycle.ts:1377](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/agent/turn-lifecycle.ts#L1377)、[memory-review.ts:22](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/agent/memory-review.ts#L22)、[memory-review.ts:102](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/agent/memory-review.ts#L102)。

### 2. 审阅模型看什么

审阅读取上次游标之后、相关上下文重置边界内的聊天，排除正在进行的 turn；没有已加载快照时从数据库取最新 200 条。实际发给模型的是过滤后的用户、助手和其他 Agent 文本，并有限长，不是任意附件和工具输出的全量转储；没有用户消息时不调用模型。

输入还包括 Agent 配置、已有的全部 essential 记忆，以及一部分与聊天词语重合较多、较新的 searchable 记忆。聊天及已有记忆在发给模型前会脱敏。后台调用使用该 Agent 的 provider 账号和可用模型，满足条件时可以选择成本更低的 provider 默认模型；它使用单独的临时会话，无工具生成 JSON。

代码：[agent-service.ts:636](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/agent-service.ts#L636)、[memory-review.ts:271](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/agent/memory-review.ts#L271)、[side-question.ts:24](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/agent/side-question.ts#L24)。

### 3. 模型提议什么，应用保存什么

[审阅提示词](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/agent/memory-review-prompt.ts#L42)要求模型保留跨聊天仍有用的偏好、稳定事实、项目和环境信息、长期决定等，排除任务进度、操作日志和临时待办。提示词明确说多数聊天可以不产生变化，也要求将聊天视为数据。

模型只能返回最多 20 个结构化操作：`add`、`update`、`forget`。新条目标为 `essential` 或 `searchable`；每条文本最多 500 字符。应用解析和校验后提交，后台审阅不允许修改人工创建或用户控制的记忆，也会检查原记录是否在审阅期间变化。

去重需要区分两层：提示词要求模型先合并相近事实，但程序本身主要按去除首尾空格后的**完全相同文本**去重，并非语义去重。一次审阅的多项修改也不保证全部作为一个事务成功。

代码：[memory-review-prompt.ts:8](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/agent/memory-review-prompt.ts#L8)、[agent-memories.ts:178](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/agent/agent-memories.ts#L178)、[memory-store.ts:184](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/memory-store.ts#L184)。

### 4. 失败和可靠性边界

这条后台审阅是机会式的，不保证每段聊天一定沉淀：审阅读取聊天后先重置计数、推进游标，再寻找模型、生成和解析结果；模型不可用、超时或结果格式错误等情况下，通常不会对同一批内容立即自动重试。资源竞争导致的特定等待场景会恢复计数和游标。进程重启还会丢失内存中的游标，后续可能重新读取可用历史。代码见 [memory-review.ts:271](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/agent/memory-review.ts#L271)。

## OpenBot：主动记忆、存储和召回

正常对话的开发者指令也要求 Agent 在遇到长期偏好、稳定事实和长期决定时调用 `openbot.remember`，并在需要旧事实时调用 `openbot.search_memories`。Agent 自己的记忆修改先暂存，当前 turn 成功完成后才提交；失败或中断会丢弃暂存。关闭后台审阅不等于关闭这些主动工具。见 [developer-instructions.ts:301](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/agent/developer-instructions.ts#L301)、[agent-memories.ts:322](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/agent/agent-memories.ts#L322)。

OpenBot 自己的记忆持久化在 Electron 用户数据目录下的 SQLite `openbot.db`。Agent、OpenBot channel 和外部消息连接分别有自己的记忆所有权和存储；它们不能简单视作同一个全局 Memory。记录包括文本、来源类型和来源 turn ID 等。每个 Agent 默认最多 256 条，每条最多 500 字符；数量上限可配置。见 [database-core.ts:56](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/database/database-core.ts#L56)、[memory-store.ts:204](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/memory-store.ts#L204)。

Agent 记忆分两类：

- **essential**：作为 Agent 上下文携带，整体有 8,192 字节预算。具体注入细节因 provider 而异；对 Claude 有单独的上下文传递处理。
- **searchable**：需要时由模型调用搜索工具读取。搜索使用 SQLite FTS5 等关键词机制，不是向量检索；并非每一轮都会自动对所有 searchable 记忆做检索。

记忆以数据方式标记，提示模型不要把它当成覆盖更高优先级指令的命令。见 [developer-instructions.ts:163](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/agent/developer-instructions.ts#L163)、[agent-memory-store.ts:230](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/agent-memory-store.ts#L230)。

频道与外部连接还有各自的记忆工具和注入路径；例如频道记忆按频道存储并注入该频道的任务上下文，相关工具调用时立即写入。这些机制与上述个人聊天后台审阅及 Agent 工具的 turn 成功后提交不同，不能把 20-turn 规则直接推广到频道。见 [channel-history.ts:613](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/channel-history.ts#L613)、[channel-service.ts:3026](https://github.com/nightly-labs/openbot/blob/ffd04b3415a270b6b149df282a3f83abef5e350e/src/backend/channel-service.ts#L3026)。

## Qgents 当前实现

```text
普通聊天 → messages 表

@Qgent → 最近 30 条群消息 + 已批准项目 Memory → 模型
  → 模型选择 create_memory → Memory 服务按触发用户权限保存
  → 普通成员：草稿；项目管理员：直接批准
```

普通聊天消息写入消息表，但不会因为发送消息就自动提取为 Memory。只有用户消息明确提及 Qgent 时，事务提交后的监听器才启动 Agent。主 Agent 初始上下文包含该群最近 30 条消息，以及项目全部已批准 Memory；聊天进入模型上下文不等于写入 Memory。

- **手工或 Agent 创建**：用户可以在项目 Memory 页面手工创建，页面调用 Memory API；模型也可以选择调用 `create_memory`，根据可见上下文或查询到的聊天自行组织内容。是否提取及提取什么由模型决定，代码没有保证自动沉淀每条消息。工具使用触发用户的项目权限，见 [MemoryTools](../src/main/java/qg/qgent/memory/tool/MemoryTools.java#L102)。
- **权限和审核**：普通成员创建的是草稿，项目管理员创建时直接批准；后续按现有状态和权限规则审核、归档，见 [MemoryService](../src/main/java/qg/qgent/memory/service/MemoryService.java#L49)。工具描述要求保存确认过的事实，但服务不会核实其真实性；管理员身份下调用也没有额外强制确认步骤。
- **存储和来源**：Memory 是项目级数据库记录，包含标题、正文、分类、标签和审核状态；当前没有结构化的来源消息 ID 或群 ID，见 [MemoryEntity](../src/main/java/qg/qgent/memory/entity/MemoryEntity.java)。
- **使用**：每次根 Agent 启动时，全量注入已批准 Memory 的标题、正文和分类；标题及正文总计默认限额 12,000 Java 字符，在批准时检查，而不是按本次请求动态截断。Agent 也可通过工具关键词搜索、分段读取 Memory。
- **可见范围**：已批准 Memory 对项目成员可见。如果将受限群里的内容写为项目 Memory 并批准，其访问范围会扩大到项目级；这是当前权限模型的直接后果。

入口和上下文见 [MessageSentListener](../src/main/java/qg/qgent/harness/integration/MessageSentListener.java#L38)、[ContextService](../src/main/java/qg/qgent/harness/context/ContextService.java#L21)、[AgentChatService](../src/main/java/qg/qgent/harness/runtime/AgentChatService.java#L116)。子 Agent 默认不复制主 Agent 的群聊上下文和全部 Memory，但可以使用聊天及 Memory 工具，权限沿用触发用户。

## 差异对照

| 维度 | OpenBot | Qgents |
| --- | --- | --- |
| 聊天自动整理 | 个人聊天和侧线程有默认开启的后台审阅；频道不走这条路径 | 未发现后台自动审阅 |
| 对话中主动记录 | Agent 根据提示词调用 `remember` | 被 `@Qgent` 后，模型可选择调用 `create_memory` |
| 默认所有权 | 后台审阅写 Agent 自身记忆；频道和外部连接有各自记忆 | 项目 Memory |
| 审核 | 后台自动变更经程序检查后写入；用户控制和手工记忆受后台审阅保护 | 普通成员草稿走审核，管理员创建直接批准 |
| 注入 | essential 携带；searchable 由模型按需搜索 | 根 Agent 全量注入已批准 Memory，工具也可搜索读取 |
| 搜索 | Agent 记忆使用 FTS5 关键词检索 | 工具对可见 Memory 做关键词子串匹配 |
| 来源 | 自动记忆有来源 turn 标识；review 使用生成的 review turn ID，不能据此认为逐条关联了原始聊天消息 | 无结构化群消息来源字段 |
| 容量 | Agent 默认 256 条，每条最多 500 字符；essential 另有字节预算 | 已批准标题及正文项目总计默认 12,000 Java 字符 |

## 对 Qgents 的启发和取舍

1. **先区分“助手个人记忆”和“项目确认知识”。** OpenBot 的个人偏好、用户事实适合服务某个助手；Qgents 的已批准 Memory 会进入项目其他成员的上下文。直接把 OpenBot 的自动写入照搬到项目 Memory，会改变知识审核和可见范围。
2. **如果将来需要自动提取，候选与发布应分别设计。** 后台模型可以提出候选，但什么内容能跨群共享、是否需要用户确认或管理员审核，是产品决策。现有管理员创建直接批准的规则不能自动等同于后台提取已经得到确认。
3. **来源和处理进度决定可追溯性与可靠性。** Qgents 当前没有结构化来源消息关联；如果未来要求解释“这条知识来自哪里”、避免重复处理或对失败重试，需要明确群范围、消息来源、处理游标和失败语义。OpenBot 的内存游标和先推进再调用模型说明其当前实现并不保证逐批可靠处理。
4. **分层加载值得参考，但需要先解决语义。** OpenBot 区分必带和按需搜索，能减少全量注入；Qgents 当前已批准知识有 12,000 字符总预算。是否引入分类和检索，应由实际知识规模与召回要求决定；关键词检索和模型自行调用也不保证一定召回。

本报告只分析现状和取舍，没有实施自动提取、修改现有权限或把建议登记为已确认需求。
