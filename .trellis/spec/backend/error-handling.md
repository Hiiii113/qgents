# 异常处理

沿用 `backend/src/main/java/qg/qgent/common/web/ApiException.java` 与 `GlobalExceptionHandler.java` 的统一响应，不为局部变更创建另一套异常协议。

- 参数错误、权限错误和未预期异常保持已有状态码、错误码及简洁业务提示。
- 内部事务回滚不新增“已回滚”等用户提示。
- Sandbox 非零命令退出与基础设施失败分开；搜索失败不能伪装成没有匹配。
- SSE 客户端断开沿用现有处理，不能再次写错误响应。

验证参考 `backend/src/test/java/qg/qgent/common/web/GlobalExceptionHandlerTest.java`。
