# Sandbox 工具与 DeepSeek Harness 对照

本次参考 DeepSeek Harness 的 `639ed015397290b3745d163aafe02ffee4aa3f84`，按 Qgents 的 IM、自有 Agent loop、持久 Workspace 和临时 Sandbox 目标采用具体处理方式，不移植其插件运行时。

来源：

- [完整工具目录](https://github.com/deepseek-ai/deepseek-harness/blob/639ed015397290b3745d163aafe02ffee4aa3f84/docs/tool-catalog.md)
- [工具编写约定](https://github.com/deepseek-ai/deepseek-harness/blob/639ed015397290b3745d163aafe02ffee4aa3f84/docs/cookbook/adding-a-tool.md)
- [读取实现](https://github.com/deepseek-ai/deepseek-harness/blob/639ed015397290b3745d163aafe02ffee4aa3f84/packages/fs/tool-fs/src/read.ts)
- [文本编辑实现](https://github.com/deepseek-ai/deepseek-harness/blob/639ed015397290b3745d163aafe02ffee4aa3f84/packages/fs/tool-fs/src/edit.ts)
- [搜索实现](https://github.com/deepseek-ai/deepseek-harness/blob/639ed015397290b3745d163aafe02ffee4aa3f84/packages/fs/tool-fs-search/src/grep.ts)
- [命令实现](https://github.com/deepseek-ai/deepseek-harness/blob/639ed015397290b3745d163aafe02ffee4aa3f84/packages/shell/tool-bash/src/index.ts)

## 已采用的处理方式

| 能力 | Qgents 行为 |
| --- | --- |
| 文件读取 | `read_file(path, startLine?, lineCount?)` 返回行号、窗口、总行数、原始字节哈希和截断信息；不在后端自动读完整文件。默认 200 行，最多 1000 行、64KiB 窗口，单行预览最多 4000 字符。 |
| 文件发现 | `glob_files(path?, glob?, limit?)` 由 Worker 一次遍历；保留隐藏配置，排除版本库元数据和常见构建目录。默认 200 个，最多 1000 个，超过上限返回 `truncated=true`。不声称截断结果是完整列表。 |
| 正文检索 | `search_code(pattern, path?, glob?, limit?)` 使用 ripgrep JSON 输出，保留路径、行号与命中正文。默认 100 个命中、最多 1000 个；每行预览最多 2000 字符。无匹配是成功空结果，执行失败保留错误。 |
| 文本编辑 | `edit_file(path, oldText, newText, replaceAll?)` 默认唯一匹配，多处或未匹配时返回明确原因；允许空替换删除文本，沿用 CRLF 文件行尾。文件版本由本次 Agent 工具实例缓存。 |
| 完整写入 | `write_file(path, content)` 自动创建父目录；未读取文件走 Worker 的创建模式，已读取文件按缓存版本替换。Worker 分别检查存在性，不通过发现列表判断。新建不能覆盖隐藏文件，替换不能借空文件哈希创建缺失文件。 |
| 补丁 | 保留 `apply_patch(path, patch)`，从内部版本缓存取哈希。无需模型复制哈希；精确补丁失败可改用文本编辑。 |
| 命令 | `run_command(command, directory?, timeoutSeconds?, waitSeconds?)` 保留 argv 数组。默认执行预算 600 秒、等待 10 秒；预算仍受 Worker/Sandbox 上限约束，等待可设 0～30 秒。管道与重定向必须显式调用 shell。 |
| 长执行 | 等待结束但命令未结束时返回 `executionId`、`status`、`running=true`。`command_output` 可继续等待并读取；`cancel_command` 停止执行。句柄只允许操作当前运行已打开工作区的 Sandbox 中的命令。 |
| 输出 | 普通命令完整输出保存在 Worker 的工作区旁侧目录，不污染仓库；模型先取得每个流最多约 16KB 的诊断尾部，通过独立字节游标继续读取。UTF-8 字符不在分页边界拆断，运行中的半行和超长单行可以继续读取。带凭证的内部 Git 命令沿用有界输出及脱敏后保存，不把原始凭证输出落盘。 |
| 错误语义 | 命令正常运行并取得非零退出码仍是完成的命令结果，`ok=true`、`exitCode!=0`；是否构建或测试通过由退出码判断。参数、基础设施、超时和取消保留错误/状态。 |
| 工具协议 | 继续通过 Spring AI `@Tool` 生成模型输入 schema，项目 loop 负责工具调用与 tool response 配对。Worker 内部协议同步维护具体参数与结果。 |

### 命令结果示例

```json
{
  "ok": true,
  "executionId": "UUID",
  "status": "SUCCEEDED",
  "running": false,
  "exitCode": 1,
  "stdout": {"text": "构建输出尾部", "truncated": true},
  "stderr": {"text": "具体错误", "truncated": false},
  "outputAvailable": true
}
```

这里的 `ok` 表示命令执行机制正常取得结果；`exitCode=1` 仍然表示构建失败。`command_output` 返回的每个流包含 `text`、`nextOffset` 和 `truncated`，后续将 `nextOffset` 作为该流的游标传入，不解析自然语言恢复执行 ID。

文件读取超过窗口时可从 `nextLine` 继续；单行预览截断单独标记在该行的 `truncated` 中。检索和文件发现不会保存一份完整结果再回给模型，超过上限时缩小条件。检索底层输出超过运行器上限且不能解析时返回失败，不伪造完整搜索结果。

命令归根 AgentRun 的 Sandbox 生命周期所有。当前控制工具只支持本次运行内的执行，不支持运行结束后继续后台执行；结束或取消根运行时销毁 Sandbox 并停止残留命令。完整输出存于 Worker 存储，不通过新的用户运行记录查询 API 暴露。

## Agent loop 工具并行

参考 DeepSeek 的有界并行池与独占屏障，在工具方法上通过 `@ParallelTool` 显式声明并行能力；未标记的工具默认串行。模型工具 schema 和原生调用格式保持不变。同一步里连续的可并行调用最多同时执行 4 个，任意一个完成就补入下一调用；遇到串行调用先等前面的调用全部结束，执行完它才处理后续调用。结果按原始 `tool_call` 顺序和 ID 回传，不按完成速度重排。

`app.agent.max-parallel-tool-calls` / `APP_AGENT_MAX_PARALLEL_TOOL_CALLS` 可设 1～16，默认 4，设为 1 时串行执行。上限属于每个 loop 当前工具组，不是全系统吞吐上限；Worker 自身执行池仍影响实际并发。子 Agent 保持同步委托，不同时修改根运行的共享工作环境。

| 可并行 | 串行屏障 |
| --- | --- |
| `read_file`、`glob_files`、`search_code` | `write_file`、`edit_file`、`apply_patch` |
| `search_chat_history`、`read_chat_message` | `activate_skill`（有激活预算和状态） |
| `list_agents`、`list_workspaces`、`github_repositories` | `create_workspace`、`use_workspace`、`call_agent`、`cancel_agent` |
| `command_output` | `run_command`、`cancel_command`、所有其他 GitHub 工具 |

首次需要代码环境时，只有环境初始化部分串行，实际文件和搜索请求在锁外执行。并行线程显式传递 AgentRun 身份与 MDC，文件版本缓存和聊天检索预算支持并发。工具失败单独回传错误，不丢失同组其他结果。取消后不再补入新调用，已启动调用结算后才释放 Sandbox；这不等于已经实现对阻塞模型请求或所有在途工具的即时中断。

调度只并行模型同一次回复中显式提出的独立调用。需要前一个结果才能确定参数时，模型必须分轮调用；调度器不推测工具间的数据依赖。通过 `run_command` 启动的长命令仍可能与后续工具同时运行，它沿用执行句柄控制，并不因串行调度就保证进程已经退出。

## 双方已有的对应能力

以下是 DeepSeek 官方工具目录的并集，不意味着每个预设都启用全部工具。

| DeepSeek | Qgents | 判断 |
| --- | --- | --- |
| `read` | `read_file` | 已采用读取窗口和行号。 |
| `grep` | `search_code` | 已采用结构化命中；保留项目命名。 |
| `glob` | `glob_files` | 已补齐路径模式发现。 |
| `edit` | `edit_file` | 已补齐唯一文本替换。 |
| `write` | `write_file` | 已收拢整文件写入，保留读取版本检查。 |
| `bash`、`pwsh` | `run_command` | 能力对应；云端 Linux Worker 用 argv，需要 shell 时显式调用，未增加 Windows 执行后端。 |
| `job_output`、`job_kill` | `command_output`、`cancel_command` | 已补命令句柄控制；不把命令、子 Agent 和业务 Task 混成统一平台。 |
| `subagent` | `call_agent` | 已有独立子 Agent loop 和根环境复用。 |
| `skill` | `activate_skill` | 已有按需读取完整 Skill。 |
| `session_event_read`、`session_event_search` 等 | `read_chat_message`、`search_chat_history` | 都支持按需检索上下文；Qgents 只向模型提供有权限的当前群聊天。 |

## DeepSeek 有、Qgents 尚未提供的能力

| DeepSeek 能力 | 当前判断 |
| --- | --- |
| `ask_user_question` | 有价值；需要接入 IM 的可持续补充消息和执行恢复，不能只增加空工具。此次未实现。 |
| `read_image` | 有价值；当前能加载聊天图片附件，但没有读取 Workspace 图片的模型工具。需要图片模型输入链路，此次未实现。 |
| `web_search`、`web_fetch` | 有价值；需实际检索/抓取服务，此次没有新增外部服务，也未假装可用。 |
| `lsp` | 大型代码任务有价值；需要 Worker 语言服务器支持，后续按实际语言需求接入。 |
| `terminal_open/read/send/signal/close/list`、持久 Bash/PowerShell | 适合交互命令和开发服务器；当前完成非交互长命令控制，未新增 PTY。 |
| `job_list` | 命令输出与取消已经补齐；当前命令句柄由返回结果提供，暂未增加统一后台执行列表。 |
| `send_message`、`interrupt_agent`、后台 subagent | 多 Agent 持续协作时有价值；当前仍为同步 `call_agent`，没有新增消息邮箱或调度平台。 |
| `list_subagent_models`、`subagent_fork` | 当前按已配置 Agent 委托，不需要先引入模型路由和会话分叉。 |
| `present` | 适合由 Agent 按需提交成果；当前聊天仍返回文本，Diff/MR 卡片尚未接通，不能把本次结构化工具结果当作已实现聊天卡片。 |
| `load_workspace_dependencies` | 当镜像提供多种可选运行环境时有价值；当前使用已配置镜像，不新增形式性的依赖工具。 |
| MCP 资源工具 | 有实际 MCP 接入后再加；当前未接入 MCP 服务。 |
| `run_code`（PTC） | 可编程组合工具，但会引入新的执行运行时；本次继续使用原生工具调用。 |
| `plugin_manager`、`cordis_inspect_list/query` | 服务于其插件架构；Qgents 不采用该插件运行时。 |
| 浏览器 `stagehand_*` | 需要浏览器执行能力，当前不在代码 Sandbox 改造范围。 |
| `todo_write`、`create_goal/get_goal/update_goal` | 其会话内目标管理不等同于旧业务 Task；Qgents 当前通过聊天记录目标，不新增状态业务。 |
| `exit_plan_mode`、`workflow`、`ralph` | 不引入强制规划/工作流；用户已确认由模型自主组织工具和 subagent。 |
| `schedule` | 需要调度和运行恢复，当前没有该能力，本次未实现。 |
| 实验性 Agent Team、`team_task_*` | 官方基础包默认关闭；不引入第二套 Task 业务。 |
| `str_replace_editor` | 独立的一体编辑入口，与 read/edit/write 功能重叠；此次无需再增加第二套编辑工具。 |

## Qgents 特有的工具与本次精简

没有同名官方工具不代表能力无用；先比较是否承担本产品独有职责。

| Qgents 能力 | 处理 |
| --- | --- |
| `github_request`、`github_clone`、`github_push`、`github_git`、`github_repositories` | 保留。它们提供团队 GitHub App 安装凭证与项目导入仓库能力，通用 shell 无法代替系统授权入口。 |
| `list_workspaces`、`create_workspace`、`use_workspace` | 保留。Qgents 的持久 Workspace 与临时 Sandbox 是独立产品资源。 |
| `cancel_agent` | 保留。按聊天触发消息取消根运行，属于 IM 执行入口。DeepSeek 的子 Agent 控制不等同于这个入口。 |
| `list_agents` | 保留。Qgents 查询可委托的配置 Agent；DeepSeek 同名控制工具查询运行中的 Agent，语义不同。 |
| `apply_patch` | 保留已有能力。DeepSeek 默认编辑工具没有同名 unified diff 入口，但文本替换与精确补丁各有用途，不能只因名称不同删除。 |
| `list_files` | 移除，替换为带条件、上限和截断信息的 `glob_files`。 |
| `replace_file` | 移除独立模型入口，合并到已读取文件的 `write_file`。内部创建与替换语义继续分开。 |
| `create_directory` | 移除独立工具和 Worker handler；写文件自动创建父目录，需要空目录时可执行 `mkdir`。 |
| `ensure_trailing_newline` | 移除独立工具和 Worker handler；通过文本编辑或写入完成。 |
| `run_development_command` | 移除固定枚举、端口、Worker handler、DTO 和旧测试；构建测试统一使用 `run_command`，返回实际诊断。 |

## 验证边界

回归覆盖读取窗口、隐藏配置发现、创建/替换存在性、版本冲突、CRLF 文本编辑、唯一匹配、搜索错误、命令句柄、输出字节分页、完整输出保存、取消与真实 HTTP 客户端路径。

本地编译和单元测试不等于真实 Docker、MySQL 和模型联调。前端聊天成果卡片、跨运行后台执行、Web/LSP/PTY 等仍未实现。
