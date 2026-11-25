package qg.qgent.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qg.qgent.common.web.ApiException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpRemoteTest {
    private final ObjectMapper json = new ObjectMapper();
    private final McpRemote remote = new McpRemote(endpoint -> {});
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private URI endpoint;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/remote/mcp", this::respond);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/remote/mcp?workspace=demo");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void listsPagedToolsAndCallsOriginalNameOverStreamableHttp() {
        assertThat(remote.list(endpoint, "user-token")).extracting(tool -> tool.name())
            .containsExactly("docs.search", "docs.read");
        var result = remote.call(endpoint, "user-token", "docs.read", Map.of("id", "page-1"));
        assertThat(result.isError()).isFalse();
        assertThat(json.valueToTree(result.structuredContent()).path("id").asText()).isEqualTo("page-1");
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.authorization()).isEqualTo("Bearer user-token");
            assertThat(request.query()).isEqualTo("workspace=demo");
        });
        var call = requests.stream().filter(request -> request.body().path("method").asText().equals("tools/call"))
            .findFirst().orElseThrow();
        assertThat(call.session()).isEqualTo("test-session");
        assertThat(call.body().path("params").path("name").asText()).isEqualTo("docs.read");
    }

    @Test
    void reportsRejectedCredentialWithoutRepeatingAnUnauthorizedCall() {
        assertThatThrownBy(() -> remote.list(endpoint, "revoked"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(McpRemote.UNAUTHORIZED));
        assertThat(requests).hasSize(1);
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonNode body = exchange.getRequestMethod().equals("DELETE")
                ? json.createObjectNode() : json.readTree(exchange.getRequestBody());
            var headers = exchange.getRequestHeaders();
            requests.add(new Request(headers.getFirst("Authorization"), headers.getFirst("Mcp-Session-Id"),
                exchange.getRequestURI().getRawQuery(), body));
            if ("Bearer revoked".equals(headers.getFirst("Authorization"))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\"");
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            String method = body.path("method").asText();
            if (method.equals("notifications/initialized") || exchange.getRequestMethod().equals("DELETE")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            if (method.equals("initialize")) exchange.getResponseHeaders().set("Mcp-Session-Id", "test-session");
            Object result = switch (method) {
                case "initialize" -> Map.of("protocolVersion", body.path("params").path("protocolVersion").asText(),
                    "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "Fixture", "version", "1"));
                case "tools/list" -> toolPage(body.path("params").path("cursor").asText());
                case "tools/call" -> Map.of("isError", false, "content", List.of(Map.of("type", "text", "text", "found")),
                    "structuredContent", body.path("params").path("arguments"));
                default -> throw new IllegalArgumentException(method);
            };
            String response = json.writeValueAsString(Map.of("id", body.get("id"), "jsonrpc", "2.0", "result", result));
            boolean sse = method.equals("tools/list");
            exchange.getResponseHeaders().set("Content-Type", sse ? "text/event-stream" : "application/json");
            byte[] bytes = (sse ? "event: message\ndata: " + response + "\n\n" : response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private Map<String, Object> toolPage(String cursor) {
        var schema = Map.of("type", "object", "properties", Map.of());
        return cursor.equals("page-2")
            ? Map.of("tools", List.of(Map.of("name", "docs.read", "inputSchema", schema)))
            : Map.of("tools", List.of(Map.of("name", "docs.search", "inputSchema", schema)), "nextCursor", "page-2");
    }

    private record Request(String authorization, String session, String query, JsonNode body) {}
}
