# 数据库与事务

使用 MySQL、MyBatis-Plus，当前模型建表脚本为 `src/main/resources/db/qgents_schema.sql`。Mapper 与实体属于业务领域；参考 `src/main/java/qg/qgent/workspace/mapper/` 和 `src/main/java/qg/qgent/workspace/entity/`。

- 按当前目标维护实体、Mapper 和建表脚本，不保留旧 Task、永久 Agent 身份及历史迁移兼容层。
- 删除兼容代码或修改 DDL 不授权操作真实数据库；涉及真实持久数据的命令先核对目标和已有授权。
- 事务回滚沿用接口原有业务提示，不暴露内部实现细节，不重复堆叠校验。
- 验证数据访问参考 `src/test/java/qg/qgent/workspace/mapper/WorkspaceMapperTest.java`；外部数据库未运行不能声称真实联调通过。
