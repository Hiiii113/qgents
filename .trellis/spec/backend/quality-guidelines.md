# 质量与验证

以 `AGENTS.md` 的执行、最小修复及提交约定为准。沿用现有写法，只解决授权范围内的实际问题；不为理论风险增加抽象、兼容分支或重复校验。

- 注释随实现更新；字段与行间说明用 `//`，类和方法可保留简洁 Javadoc。
- 代码变更完成编译与相关可执行验证。Windows 使用 `./mvnw.cmd -pl backend test -Dtest=<相关测试类>`，编译使用 `./mvnw.cmd test-compile -DskipTests`；需要完整打包验证时使用 `./mvnw.cmd clean package -q`。
- 测试参考 `backend/src/test/java/qg/qgent/sandbox/tool/CodingToolsTest.java`。真实服务验证与单元测试分别报告，核对外部测试目标，不擅自触碰持久数据。
- 文档变更检查链接、内容和 `git diff --check`；Trellis 配置还检查 JSON/TOML、Python 脚本和上下文加载，不机械跑全量业务测试。
- 验证通过后按独立主题主动 commit；Conventional Commits 前缀、中文具体标题，正文记录机制和实际验证；不 push。
