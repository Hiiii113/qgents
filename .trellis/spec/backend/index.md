# Qgents 后端规范

## Pre-Development Checklist

先读根目录 `AGENTS.md`；涉及业务行为、架构或权限时读 `docs/target.md` 相关章节。术语沿用 `GLOSSARY.md`。
按本次修改读取下表中的规范，检查前端调用时到 `D:/code/qgents-fronted`。

| 文档 | 适用修改 |
| --- | --- |
| [目录结构](directory-structure.md) | 新增或移动代码、领域边界 |
| [数据库](database-guidelines.md) | 实体、Mapper、SQL 和事务 |
| [异常处理](error-handling.md) | API、业务失败和回滚 |
| [质量与验证](quality-guidelines.md) | 所有实施任务 |
| [日志](logging-guidelines.md) | 日志、执行追踪 |

## Quality Check

按质量规范运行相关检查；核对接口调用、权限、SQL、配置、测试和文档的一致性。只提交本次工作，不 push。
