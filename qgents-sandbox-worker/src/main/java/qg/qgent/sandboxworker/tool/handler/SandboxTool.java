package qg.qgent.sandboxworker.tool.handler;

import qg.qgent.sandboxworker.tool.dto.ToolContext;
import qg.qgent.sandboxworker.tool.dto.ToolResult;

import java.util.Map;

/**
 * 服务端注册的单个沙箱工具。
 */
public interface SandboxTool {
    String name();

    boolean requiresRepository();

    ToolResult execute(ToolContext context, Map<String, Object> arguments) throws InterruptedException;
}
