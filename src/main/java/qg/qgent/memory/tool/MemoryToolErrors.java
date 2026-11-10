package qg.qgent.memory.tool;

import qg.qgent.common.web.ApiException;
import qg.qgent.harness.tool.ToolResult;

import java.util.function.Supplier;

/** 项目记忆工具的失败映射：业务异常保留错误码，其他运行时失败统一为 MEMORY_UNAVAILABLE。 */
final class MemoryToolErrors {

    static ToolResult call(Supplier<ToolResult> action) {
        try {
            return action.get();
        } catch (ApiException e) {
            return ToolResult.error(e.code(), e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.error("MEMORY_UNAVAILABLE", "项目记忆操作失败: " + e.getClass().getSimpleName());
        }
    }

    private MemoryToolErrors() {
    }
}
