package qg.qgent.harness.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.Disposable;

class OpenAiModelClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> fixtureFailure = new AtomicReference<>();
    private volatile Responder responder;
    private HttpServer server;
    private OpenAiModelClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
            "/chat/completions",
            exchange -> {
                try {
                    requests.add(
                        exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                    JsonNode body = JSON.readTree(exchange.getRequestBody());
                    bodies.add(body);
                    responder.respond(exchange, body);
                } catch (Exception exception) {
                    fixtureFailure.compareAndSet(null, exception);
                } finally {
                    exchange.close();
                }
            });
        server.start();
        client = client(server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        client.destroy();
        server.stop(0);
        assertThat(fixtureFailure.get()).as("本地请求响应夹具失败").isNull();
    }

    @Test
    void streamPostsToChatCompletionsAndForwardsTextDeltas() {
        responder =
            (exchange, body) ->
                sse(
                    exchange,
                    chunk(Map.of("role", "assistant", "content", "你好")),
                    chunk(Map.of("content", "，世界")),
                    finish("stop"));
        List<String> deltas = new ArrayList<>();

        ModelClient.Reply reply = client.stream(request("你好"), deltas::add).block(WAIT);

        assertThat(deltas).containsExactly("你好", "，世界");
        assertThat(reply.message().text()).isEqualTo("你好，世界");
        assertThat(reply.message().toolCalls()).isEmpty();
        assertThat(reply.finishReason()).isEqualTo("stop");
        assertThat(requests).containsExactly("POST /chat/completions");
        JsonNode body = bodies.get(0);
        assertThat(body.get("model").asText()).isEqualTo("test-model");
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.get("max_tokens").asLong()).isEqualTo(4096);
        assertThat(body.get("tool_choice").asText()).isEqualTo("none");
        assertThat(body.has("tools")).isFalse();
    }

    @Test
    void streamAssemblesFragmentedToolArgumentsAndReasoning() {
        responder =
            (exchange, body) ->
                sse(
                    exchange,
                    chunk(
                        Map.of(
                            "role",
                            "assistant",
                            "reasoning_content",
                            "先想",
                            "tool_calls",
                            List.of(
                                Map.of(
                                    "index",
                                    0,
                                    "id",
                                    "call_1",
                                    "type",
                                    "function",
                                    "function",
                                    Map.of("name", "read_file", "arguments", "{\"pa"))))),
                    chunk(
                        Map.of(
                            "reasoning",
                            "一下",
                            "tool_calls",
                            List.of(
                                Map.of(
                                    "index",
                                    0,
                                    "function",
                                    Map.of("arguments", "th\":\"a.txt\"}"))))),
                    """
                    {"id":"chatcmpl-1","created":1700000000,"model":"test-model",
                     "choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],
                     "usage":{"prompt_tokens":9,"completion_tokens":4,"total_tokens":13}}
                    """.replace("\n", ""));

        ModelClient.Reply reply = client.stream(request("读 a.txt"), delta -> {}).block(WAIT);

        assertThat(reply.finishReason()).isEqualTo("tool_calls");
        assertThat(reply.message().toolCalls())
            .containsExactly(
                new ModelMessage.ToolCall("call_1", "read_file", "{\"path\":\"a.txt\"}"));
        assertThat(reply.message().reasoningContent()).isEqualTo("先想一下");
        assertThat(reply.message().text()).isEmpty();
        assertThat(reply.usage()).isEqualTo(new ModelClient.Usage(9, 4, 0, 0));
    }

    @Test
    void streamRequestsIncludeUsageAndCapturesTrailingUsageWithCacheAndReasoning() {
        responder =
            (exchange, body) ->
                sse(
                    exchange,
                    chunk(Map.of("role", "assistant", "content", "完成")),
                    finish("stop"),
                    """
                    {"id":"chatcmpl-1","created":1700000000,"model":"test-model","choices":[],
                     "usage":{"prompt_tokens":120,"completion_tokens":35,"total_tokens":155,
                              "prompt_tokens_details":{"cached_tokens":80},
                              "completion_tokens_details":{"reasoning_tokens":10}}}
                    """.replace("\n", ""));

        ModelClient.Reply reply = client.stream(request("统计"), delta -> {}).block(WAIT);

        assertThat(bodies.get(0).path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(reply.usage()).isEqualTo(new ModelClient.Usage(120, 35, 80, 10));
    }

    @Test
    void streamCapturesDeepSeekPromptCacheHitTokensFromAdditionalProperties() {
        responder =
            (exchange, body) ->
                sse(
                    exchange,
                    chunk(Map.of("role", "assistant", "content", "好")),
                    """
                    {"id":"chatcmpl-1","created":1700000000,"model":"test-model",
                     "choices":[{"index":0,"delta":{},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":200,"completion_tokens":20,"total_tokens":220,
                              "prompt_cache_hit_tokens":150}}
                    """.replace("\n", ""));

        ModelClient.Reply reply = client.stream(request("缓存"), delta -> {}).block(WAIT);

        assertThat(reply.usage()).isEqualTo(new ModelClient.Usage(200, 20, 150, 0));
    }

    @Test
    void streamSendsReasoningAndToolResultsInOrder() {
        responder =
            (exchange, body) ->
                sse(exchange, chunk(Map.of("role", "assistant", "content", "好")), finish("stop"));
        ModelClient.Request request =
            new ModelClient.Request(
                List.of(
                    new ModelMessage.SystemMessage("系统"),
                    new ModelMessage.UserMessage("读文件"),
                    new ModelMessage.AssistantMessage(
                        "",
                        List.of(
                            new ModelMessage.ToolCall("call_1", "read_file", "{\"path\":\"a.txt\"}"),
                            new ModelMessage.ToolCall("call_2", "list_dir", "{}")),
                        "思路"),
                    new ModelMessage.ToolResponseMessage(
                        List.of(
                            new ModelMessage.ToolResponse(
                                "call_1", "read_file", "{\"content\":\"A\"}"),
                            new ModelMessage.ToolResponse("call_2", "list_dir", "[]")))),
                List.of());

        client.stream(request, delta -> {}).block(WAIT);

        JsonNode messages = bodies.get(0).get("messages");
        assertThat(messages.size()).isEqualTo(5);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("user");
        JsonNode assistant = messages.get(2);
        assertThat(assistant.get("role").asText()).isEqualTo("assistant");
        assertThat(assistant.get("reasoning_content").asText()).isEqualTo("思路");
        assertThat(assistant.get("tool_calls").size()).isEqualTo(2);
        JsonNode firstCall = assistant.get("tool_calls").get(0);
        assertThat(firstCall.get("id").asText()).isEqualTo("call_1");
        assertThat(firstCall.get("type").asText()).isEqualTo("function");
        assertThat(firstCall.get("function").get("name").asText()).isEqualTo("read_file");
        assertThat(messages.get(3).get("role").asText()).isEqualTo("tool");
        assertThat(messages.get(3).get("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(messages.get(3).get("content").asText()).isEqualTo("{\"content\":\"A\"}");
        assertThat(messages.get(4).get("role").asText()).isEqualTo("tool");
        assertThat(messages.get(4).get("tool_call_id").asText()).isEqualTo("call_2");
    }

    @Test
    void streamSendsImagesAsUriContentParts() {
        responder = (exchange, body) -> sse(exchange, finish("stop"));
        ModelClient.Request request =
            new ModelClient.Request(
                List.of(
                    new ModelMessage.UserMessage(
                        "看图", List.of(URI.create("https://example.com/a.png")))),
                List.of());

        client.stream(request, delta -> {}).block(WAIT);

        JsonNode content = bodies.get(0).get("messages").get(0).get("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).get("type").asText()).isEqualTo("text");
        assertThat(content.get(0).get("text").asText()).isEqualTo("看图");
        assertThat(content.get(1).get("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).get("image_url").get("url").asText())
            .isEqualTo("https://example.com/a.png");
    }

    @Test
    void completeUsesNonStreamingRequest() {
        responder = (exchange, body) -> json(exchange, completion("摘要"));

        assertThat(client.complete("系统", "用户文本")).isEqualTo("摘要");

        JsonNode body = bodies.get(0);
        assertThat(body.has("stream")).isFalse();
        assertThat(body.get("tool_choice").asText()).isEqualTo("none");
    }

    @Test
    void completeTreatsEmptyChoicesAsEmptyText() {
        responder =
            (exchange, body) ->
                json(
                    exchange,
                    Map.of(
                        "id",
                        "chatcmpl-1",
                        "created",
                        1_700_000_000L,
                        "model",
                        "test-model",
                        "choices",
                        List.of()));

        assertThat(client.complete("系统", "用户文本")).isEmpty();
    }

    @Test
    void streamWithoutFinishReasonFailsInsteadOfReturningTools() {
        responder =
            (exchange, body) ->
                sse(
                    exchange,
                    chunk(
                        Map.of(
                            "role",
                            "assistant",
                            "tool_calls",
                            List.of(
                                Map.of(
                                    "index",
                                    0,
                                    "id",
                                    "call_1",
                                    "type",
                                    "function",
                                    "function",
                                    Map.of("name", "read_file", "arguments", "{}"))))));

        assertThatThrownBy(() -> client.stream(request("读文件"), delta -> {}).block(WAIT))
            .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(30)
    void cancelClosesConnection(boolean streamStarted) throws Exception {
        try (ServerSocket peer =
            new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            peer.setSoTimeout(15_000);
            OpenAiModelClient client = client(peer.getLocalPort());
            try {
                AtomicBoolean signalled = new AtomicBoolean();
                CountDownLatch receivedText = new CountDownLatch(1);
                Disposable subscription =
                    client
                        .stream(request("你好"), delta -> receivedText.countDown())
                        .subscribe(
                            reply -> signalled.set(true), error -> signalled.set(true));
                try (Socket socket = peer.accept()) {
                    socket.setSoTimeout(15_000);
                    InputStream input = socket.getInputStream();
                    readRequest(input);
                    if (streamStarted) {
                        String headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                            + "Connection: close\r\n\r\n";
                        String firstChunk = "data: " + chunk(Map.of("content", "你好")) + "\n\n";
                        OutputStream output = socket.getOutputStream();
                        output.write((headers + firstChunk).getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        assertThat(receivedText.await(5, TimeUnit.SECONDS)).isTrue();
                    }
                    subscription.dispose();
                    assertThat(input.read()).as("取消后应断开模型连接").isEqualTo(-1);
                }
                assertThat(signalled).isFalse();
            } finally {
                client.destroy();
            }
        }
    }

    private static OpenAiModelClient client(int port) {
        return new OpenAiModelClient(
            "test-key", "http://127.0.0.1:" + port, "test-model", 0.2, 4096, JSON);
    }

    private static ModelClient.Request request(String text) {
        return new ModelClient.Request(
            List.of(new ModelMessage.SystemMessage("系统"), new ModelMessage.UserMessage(text)),
            List.of());
    }

    private static void sse(HttpExchange exchange, String... chunks) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            for (String chunk : chunks) {
                output.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void json(HttpExchange exchange, Object payload) throws IOException {
        byte[] body = JSON.writeValueAsBytes(payload);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static Map<String, Object> completion(String content) {
        return Map.of(
            "id",
            "chatcmpl-1",
            "created",
            1_700_000_000L,
            "model",
            "test-model",
            "choices",
            List.of(
                Map.of(
                    "index", 0,
                    "message", Map.of("role", "assistant", "content", content),
                    "finish_reason", "stop")));
    }

    private static String chunk(Object delta) {
        return jsonText(
            Map.of(
                "id",
                "chatcmpl-1",
                "created",
                1_700_000_000L,
                "model",
                "test-model",
                "choices",
                List.of(Map.of("index", 0, "delta", delta))));
    }

    private static String finish(String reason) {
        return jsonText(
            Map.of(
                "id",
                "chatcmpl-1",
                "created",
                1_700_000_000L,
                "model",
                "test-model",
                "choices",
                List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", reason))));
    }

    private static String jsonText(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void readRequest(InputStream input) throws IOException {
        String start = readLine(input);
        if (!start.startsWith("POST /chat/completions ")) {
            throw new IllegalStateException("意外的请求行：" + start);
        }
        int length = 0;
        for (String line = readLine(input); !line.isEmpty(); line = readLine(input)) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        if (input.readNBytes(length).length != length) {
            throw new IllegalStateException("请求体不完整");
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int next = input.read(); next >= 0 && next != '\n'; next = input.read()) {
            if (next != '\r') {
                line.write(next);
            }
        }
        return line.toString(StandardCharsets.US_ASCII);
    }

    private interface Responder {
        void respond(HttpExchange exchange, JsonNode body) throws IOException;
    }
}
