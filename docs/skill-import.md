# Skill 导入

在项目 Skill 页面选择一个 UTF-8 编码的 `SKILL.md`，检查预览后选择可见性并确认导入。文件需包含 YAML frontmatter 中的非空 `name`、`description` 和非空正文，大小不超过 256 KiB。导入后可编辑完整文件，名称和描述从文件重新解析。

第一版只保存这个文件；它引用的脚本、参考资料、其他 Skill 和外部工具不会被安装，宿主专有字段也不保证生效。

## 已有开发数据库

初始化 SQL 只在数据库首次创建时执行，更新代码不会自动修改现有 `skills` 表。已有数据库需要先备份，再执行以下步骤；不要在升级完成前启动新版服务：

1. 添加描述列：`ALTER TABLE skills ADD COLUMN description TEXT NULL AFTER name;`
2. 旧的手工 Skill 不一定是有效的 `SKILL.md`。由使用者确认并备份需要保留的内容后，移除这些旧记录；不要把旧正文直接当作有效文件。
3. 确认表中没有 `description IS NULL` 的记录后，执行 `ALTER TABLE skills MODIFY description TEXT NOT NULL COMMENT 'Skill用途描述';`。启动新版服务后，将需要保留的内容整理为有效文件并重新导入。

新数据库直接使用 `src/main/resources/db/qgents_schema.sql`，不需要以上步骤。
