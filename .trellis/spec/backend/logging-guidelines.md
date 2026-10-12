# 日志与追踪

日志配置在 `src/main/resources/logback-spring.xml`；沿用现有 SLF4J 写法，例如 `common/web/GlobalExceptionHandler.java` 的 `@Slf4j` 和参数化日志。

- 日志说明实际事件及失败原因，沿用现有级别与执行上下文。
- Agent 和命令记录关联实际 AgentRun、Workspace，不引入独立 Task 业务。
- 不因理论性隐私风险擅自删除或屏蔽现有日志；范围外风险说明后由用户决定。
- 不向代码、日志、提交或对外输出写入真实凭证。
