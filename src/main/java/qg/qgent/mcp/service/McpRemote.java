package qg.qgent.mcp.service;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import qg.qgent.common.web.ApiException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

@Component
class McpRemote {
    static final String UNAUTHORIZED = "MCP_REMOTE_UNAUTHORIZED";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private final Consumer<URI> checkHost;

    McpRemote() {
        this(McpEndpoint::requirePublicHost);
    }

    McpRemote(Consumer<URI> checkHost) {
        this.checkHost = checkHost;
    }

    List<McpSchema.Tool> list(URI endpoint, String token) {
        return request(endpoint, token, client -> {
            List<McpSchema.Tool> tools = new ArrayList<>();
            var cursors = new HashSet<String>();
            String cursor = McpSchema.FIRST_PAGE;
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            do {
                var response = client.listTools(cursor);
                tools.addAll(response.tools());
                cursor = response.nextCursor();
                if (cursor != null && (System.nanoTime() >= deadline || !cursors.add(cursor))) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP_CATALOG_INCOMPLETE", "MCP 工具目录分页未完成");
                }
            } while (cursor != null);
            return tools;
        });
    }

    McpSchema.CallToolResult call(URI endpoint, String token, String tool, Map<String, Object> arguments) {
        return request(endpoint, token, client -> client.callTool(new McpSchema.CallToolRequest(tool, arguments)));
    }

    private <T> T request(URI endpoint, String token, Function<McpSyncClient, T> action) {
        checkHost.accept(endpoint);
        String base = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        String pathAndQuery = endpoint.toASCIIString().substring(base.length());
        var transport = HttpClientStreamableHttpTransport.builder(base)
            .endpoint(pathAndQuery.isEmpty() ? "/" : pathAndQuery)
            .customizeClient(builder -> builder.followRedirects(HttpClient.Redirect.NEVER))
            .connectTimeout(Duration.ofSeconds(5))
            .openConnectionOnStartup(false)
            .resumableStreams(false)
            .httpRequestCustomizer((request, method, uri, body, context) -> request.header("Authorization", "Bearer " + token))
            .build();
        try (McpSyncClient client = McpClient.sync(transport)
            .clientInfo(new McpSchema.Implementation("Qgents", "1.0"))
            .initializationTimeout(TIMEOUT).requestTimeout(TIMEOUT).build()) {
            client.initialize();
            return action.apply(client);
        } catch (RuntimeException error) {
            throw remoteError(error);
        }
    }

    private RuntimeException remoteError(RuntimeException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof McpHttpClientTransportAuthorizationException auth) {
                return auth.getResponseInfo().statusCode() == 401
                    ? new ApiException(HttpStatus.BAD_GATEWAY, UNAUTHORIZED, "MCP 服务拒绝了当前凭证")
                    : new ApiException(HttpStatus.BAD_GATEWAY, "MCP_REMOTE_FORBIDDEN", "当前凭证无权在 MCP 服务上执行此操作");
            }
            if (cause instanceof TimeoutException) {
                return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "MCP_REMOTE_TIMEOUT", "MCP 服务响应超时");
            }
        }
        if (error instanceof McpError || error instanceof McpTransportException) {
            return new ApiException(HttpStatus.BAD_GATEWAY, "MCP_REMOTE_ERROR", "MCP 服务请求失败");
        }
        return error;
    }
}
