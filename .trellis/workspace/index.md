# 本地开发会话记录

此目录保存各开发者的 Trellis 会话索引和日志。个人子目录仅本地保存，不纳入版本控制；本说明随项目共享。

首次使用时，在仓库根目录执行：

```powershell
python .trellis/scripts/init_developer.py <开发者名称>
```

脚本会创建本地身份文件、个人索引和会话日志。已有记录可通过以下命令查看：

```powershell
python .trellis/scripts/get_context.py
```

任务上下文位于 `.trellis/tasks/`，已完成任务归档到其 `archive/` 子目录后仅本地保存。需要长期共享的规范写入 `.trellis/spec/`，正式产品资料写入 `docs/`。
