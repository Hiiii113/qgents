package qg.qgent.mcp.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import qg.qgent.common.web.ApiException;
import qg.qgent.harness.tool.ToolCallback;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.mcp.entity.McpServer;
import qg.qgent.mcp.entity.McpServer.Scope;
import qg.qgent.mcp.service.McpOperations;
import qg.qgent.mcp.service.McpOperations.CredentialVersion;
import qg.qgent.mcp.service.McpOperations.RunActor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
public class McpToolCallbacks {
    private final McpOperations operations;
    private final ObjectMapper json;
    private final MeterRegistry registry;

    public McpToolCallbacks(McpOperations operations, ObjectMapper json) {
        this(operations, json, new SimpleMeterRegistry());
    }

    @Autowired
    public McpToolCallbacks(McpOperations operations, ObjectMapper json, MeterRegistry registry) {
        this.operations = operations;
        this.json = json;
        this.registry = registry;
    }

    public List<ToolCallback> forRun(Long projectId, Long agentId, Long actor) {
        RunActor run = new RunActor(projectId, agentId, actor);
        List<ToolCallback> callbacks = new ArrayList<>();
        for (McpServer server : operations.assigned(run)) {
            try {
                var catalog = operations.discover(run, server.id());
                for (McpSchema.Tool tool : catalog.tools()) callbacks.add(tool(run, server, catalog.version(), tool));
            } catch (ApiException e) {
                callbacks.add(status(server, e));
            }
        }
        return callbacks;
    }

    private ToolCallback tool(RunActor run, McpServer server, CredentialVersion version, McpSchema.Tool tool) {
        String name = toolName(server, tool.name());
        String description = label(server) + "。" + (tool.description() == null ? tool.name() : tool.description())
            + "。同一服务有项目共享和个人连接时，按用户指定的连接使用，意图不清时先询问用户。";
        String inputSchema = encode(tool.inputSchema());
        return callback(name, description, inputSchema, arguments -> {
            long startNanos = System.nanoTime();
            try {
                var result = operations.call(run, server.id(), version, tool.name(), arguments);
                boolean isError = Boolean.TRUE.equals(result.isError());
                recordMcpCall(isError ? "tool_error" : "ok", System.nanoTime() - startNanos);
                ToolResult response = isError
                    ? ToolResult.error("MCP_TOOL_ERROR", "MCP 工具报告执行失败") : ToolResult.ok();
                response.put("content", result.content());
                if (result.structuredContent() != null) response.put("structuredContent", result.structuredContent());
                return response;
            } catch (ApiException e) {
                String outcome =
                    e.status() == HttpStatus.UNAUTHORIZED || e.status() == HttpStatus.FORBIDDEN
                        ? "auth_error"
                        : "error";
                recordMcpCall(outcome, System.nanoTime() - startNanos);
                return ToolResult.error(e.code(), e.getMessage());
            } catch (RuntimeException e) {
                recordMcpCall("error", System.nanoTime() - startNanos);
                throw e;
            }
        });
    }

    private ToolCallback status(McpServer server, ApiException error) {
        String name = toolName(server, "") + "_status";
        String description = "检查" + label(server) + "当前无法加载工具的原因。";
        String inputSchema = encode(Map.of("type", "object", "properties", Map.of()));
        return callback(name, description, inputSchema, ignored -> ToolResult.error(error.code(), error.getMessage()));
    }

    private ToolCallback callback(
        String name,
        String description,
        String inputSchema,
        Function<Map<String, Object>, ToolResult> call) {
        return new ToolCallback(name, description, inputSchema, false, input -> {
            Map<String, Object> arguments;
            try {
                arguments = json.readValue(input, new TypeReference<>() {});
            } catch (JsonProcessingException e) {
                return encode(ToolResult.error("INVALID_ARGUMENT", "MCP 工具参数必须是 JSON 对象"));
            }
            if (arguments == null) return encode(ToolResult.error("INVALID_ARGUMENT", "MCP 工具参数必须是 JSON 对象"));
            return encode(call.apply(arguments));
        });
    }

    private String toolName(McpServer server, String name) {
        String slug = name.replaceAll("[^A-Za-z0-9_-]", "_");
        if (slug.length() > 26) slug = slug.substring(0, 26);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            return "mcp_" + Long.toString(server.id(), 36) + "_" + slug + "_" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String label(McpServer server) {
        return (server.scope() == Scope.SHARED ? "项目共享连接" : "个人连接") + "「" + server.name() + "」";
    }

    private void recordMcpCall(String outcome, long durationNanos) {
        Counter.builder("qgents.mcp.calls.total")
            .description("Total external MCP tool calls")
            .tag("outcome", outcome)
            .register(registry)
            .increment();
        Timer.builder("qgents.mcp.call.duration")
            .description("Duration of external MCP tool calls")
            .tag("outcome", outcome)
            .register(registry)
            .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode MCP tool definition or result", e);
        }
    }
}
