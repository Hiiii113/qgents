package qg.qgent.harness.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.OpenAIClientAsyncImpl;
import com.openai.client.okhttp.OkHttpClient;
import com.openai.core.ClientOptions;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import com.openai.helpers.ChatCompletionAccumulator;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 用官方 OpenAI Java SDK 调用 OpenAI 兼容接口；流式的超时与重试由运行层控制。
 */
@Service
public class OpenAiModelClient implements ModelClient, DisposableBean {
    // 兼容实现可能用不同字段名返回思考过程，同一分片只取一个字段，按分片累计。
    private static final List<String> REASONING_DELTA_FIELDS =
        List.of("reasoning_content", "reasoning");
    private static final String REASONING_CONTENT_FIELD = "reasoning_content";

    private final String model;
    private final double temperature;
    private final long maxTokens;
    private final ObjectMapper objectMapper;
    // 所有请求共用一条连接池；单次流只包装传输，不能关掉共享实例。
    private final OkHttpClient transport;
    private final OpenAIClientAsync client;

    public OpenAiModelClient(
        @Value("${app.ai.api-key}") String apiKey,
        @Value("${app.ai.base-url}") String baseUrl,
        @Value("${app.ai.model}") String model,
        @Value("${app.ai.temperature}") double temperature,
        @Value("${app.ai.max-tokens}") long maxTokens,
        ObjectMapper objectMapper) {
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.objectMapper = objectMapper;
        this.transport = OkHttpClient.builder().build();
        this.client =
            new OpenAIClientAsyncImpl(
                ClientOptions.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .httpClient(transport)
                    // 非流式调用保持后台提炼原有的 60s 上限与 3 次重试。
                    .timeout(Duration.ofSeconds(60))
                    .maxRetries(3)
                    .build());
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        Request request =
            new Request(
                List.of(
                    new ModelMessage.SystemMessage(systemPrompt),
                    new ModelMessage.UserMessage(userPrompt)),
                List.of());
        ChatCompletion completion =
            client.chat().completions().create(params(request)).join();
        // 空 choices 交给调用方按空回复处理，不要在这里换个异常类型。
        if (completion.choices().isEmpty()) {
            return "";
        }
        return completion.choices().get(0).message().content().orElse("");
    }

    /**
     * 订阅一轮流式回复；取消时先断开底层请求，避免首包之前留下悬挂连接。
     */
    @Override
    public Mono<Reply> stream(Request request, Consumer<String> textDelta) {
        return Mono.create(
            sink -> {
                StreamConnection connection = new StreamConnection(transport);
                sink.onCancel(connection::cancel);
                ChatCompletionAccumulator accumulator = ChatCompletionAccumulator.create();
                StringBuilder reasoning = new StringBuilder();
                AsyncStreamResponse<ChatCompletionChunk> stream =
                    client.withOptions(
                            options ->
                                options
                                    .httpClient(connection)
                                    // 流式响应不能由 SDK 重试或限时，整轮时限由 AgentLoop 控制。
                                    .maxRetries(0)
                                    .timeout(
                                        Timeout.builder().request(Duration.ZERO).build()))
                        .chat()
                        .completions()
                        .createStreaming(params(request));
                stream.subscribe(
                    new AsyncStreamResponse.Handler<>() {
                        @Override
                        public void onNext(ChatCompletionChunk chunk) {
                            if (connection.cancelled.get() || chunk.choices().isEmpty()) return;
                            appendReasoning(chunk, reasoning);
                            // DeepSeek 可把 usage 和 finish_reason 放在同一帧。SDK 聚合器把含 usage 的帧当成独立尾帧。
                            accumulator.accumulate(chunk.usage().isPresent()
                                ? chunk.toBuilder().usage(Optional.empty()).build()
                                : chunk);
                            for (ChatCompletionChunk.Choice choice : chunk.choices()) {
                                choice.delta().content().filter(text -> !text.isEmpty()).ifPresent(textDelta);
                            }
                        }

                        @Override
                        public void onComplete(Optional<Throwable> error) {
                            if (error.isPresent()) {
                                sink.error(error.get());
                                return;
                            }
                            try {
                                sink.success(reply(accumulator, reasoning));
                            } catch (RuntimeException truncated) {
                                // 没有结束原因的流不完整，截断的工具调用不能交给上层执行。
                                sink.error(truncated);
                            }
                        }
                    }, connection::startReading);
            });
    }

    @Override
    public void destroy() {
        client.close();
    }

    private ChatCompletionCreateParams params(Request request) {
        ChatCompletionCreateParams.Builder builder =
            ChatCompletionCreateParams.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens);
        request.messages().forEach(message -> addMessage(builder, message));
        if (request.tools().isEmpty()) {
            builder.toolChoice(ChatCompletionToolChoiceOption.Auto.NONE);
        } else {
            builder
                .tools(request.tools().stream().map(this::tool).toList())
                .toolChoice(ChatCompletionToolChoiceOption.Auto.AUTO);
        }
        return builder.build();
    }

    private static void addMessage(
        ChatCompletionCreateParams.Builder builder, ModelMessage message) {
        switch (message) {
            case ModelMessage.SystemMessage system -> builder.addSystemMessage(system.text());
            case ModelMessage.UserMessage user -> addUserMessage(builder, user);
            case ModelMessage.AssistantMessage assistant -> addAssistantMessage(builder, assistant);
            case ModelMessage.ToolResponseMessage responses -> addToolResponses(builder, responses);
        }
    }

    private static void addUserMessage(
        ChatCompletionCreateParams.Builder builder, ModelMessage.UserMessage message) {
        if (message.images().isEmpty()) {
            builder.addUserMessage(message.text());
            return;
        }
        List<ChatCompletionContentPart> parts = new ArrayList<>();
        if (!message.text().isEmpty()) {
            parts.add(
                ChatCompletionContentPart.ofText(
                    ChatCompletionContentPartText.builder().text(message.text()).build()));
        }
        for (URI image : message.images()) {
            parts.add(
                ChatCompletionContentPart.ofImageUrl(
                    ChatCompletionContentPartImage.builder()
                        .imageUrl(
                            ChatCompletionContentPartImage.ImageUrl.builder()
                                .url(image.toString())
                                .build())
                        .build()));
        }
        builder.addUserMessageOfArrayOfContentParts(parts);
    }

    private static void addAssistantMessage(
        ChatCompletionCreateParams.Builder builder, ModelMessage.AssistantMessage message) {
        ChatCompletionAssistantMessageParam.Builder param =
            ChatCompletionAssistantMessageParam.builder().content(message.text());
        for (ModelMessage.ToolCall call : message.toolCalls()) {
            param.addToolCall(
                ChatCompletionMessageFunctionToolCall.builder()
                    .id(call.id())
                    .function(
                        ChatCompletionMessageFunctionToolCall.Function.builder()
                            .name(call.name())
                            .arguments(call.arguments())
                            .build())
                    .build());
        }
        if (message.reasoningContent() != null) {
            param.putAdditionalProperty(
                REASONING_CONTENT_FIELD, JsonValue.from(message.reasoningContent()));
        }
        builder.addMessage(param.build());
    }

    private static void addToolResponses(
        ChatCompletionCreateParams.Builder builder, ModelMessage.ToolResponseMessage message) {
        // 结果按执行顺序逐条回传，每条用原始调用 ID 关联。
        for (ModelMessage.ToolResponse response : message.responses()) {
            builder.addMessage(
                ChatCompletionToolMessageParam.builder()
                    .toolCallId(response.id())
                    .content(response.responseData())
                    .build());
        }
    }

    private ChatCompletionTool tool(ToolDefinition definition) {
        FunctionDefinition.Builder function =
            FunctionDefinition.builder()
                .name(definition.name())
                .parameters(
                    FunctionParameters.builder()
                        .putAllAdditionalProperties(schemaProperties(definition))
                        .build());
        if (definition.description() != null) {
            function.description(definition.description());
        }
        return ChatCompletionTool.ofFunction(
            ChatCompletionFunctionTool.builder().function(function.build()).build());
    }

    /**
     * 工具 schema 由上游拼成 JSON 对象字符串，这里按顶层字段摊进 SDK 的参数对象。
     */
    private Map<String, JsonValue> schemaProperties(ToolDefinition definition) {
        JsonNode schema;
        try {
            schema = objectMapper.readTree(definition.inputSchema());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "工具 " + definition.name() + " 的参数 schema 不是合法 JSON", exception);
        }
        Map<String, JsonValue> properties = new HashMap<>();
        schema.fields()
            .forEachRemaining(
                field -> properties.put(field.getKey(), JsonValue.fromJsonNode(field.getValue())));
        return properties;
    }

    private static Reply reply(
        ChatCompletionAccumulator accumulator, CharSequence reasoning) {
        // 流在结束原因之前断掉时 chatCompletion() 会抛异常，回复整体失败而不是交出半份工具调用。
        ChatCompletion.Choice choice = accumulator.chatCompletion().choices().get(0);
        ChatCompletionMessage message = choice.message();
        List<ModelMessage.ToolCall> toolCalls =
            message
                .toolCalls()
                .orElse(List.of())
                .stream()
                .map(ChatCompletionMessageToolCall::asFunction)
                .map(
                    call ->
                        new ModelMessage.ToolCall(
                            call.id(), call.function().name(), call.function().arguments()))
                .toList();
        return new Reply(
            new ModelMessage.AssistantMessage(
                message.content().orElse(""),
                toolCalls,
                reasoning.isEmpty() ? null : reasoning.toString()),
            choice.finishReason().asString());
    }

    private static void appendReasoning(
        ChatCompletionChunk chunk, StringBuilder reasoning) {
        for (ChatCompletionChunk.Choice choice : chunk.choices()) {
            Map<String, JsonValue> extras = choice.delta()._additionalProperties();
            for (String field : REASONING_DELTA_FIELDS) {
                JsonValue value = extras.get(field);
                if (value != null) {
                    value.asString().ifPresent(reasoning::append);
                    break;
                }
            }
        }
    }

    /**
     * 单次流的传输包装：记下 SDK 交给 OkHttp 的请求 Future，取消订阅时连同首包前的连接一起关掉。
     */
    private static final class StreamConnection implements HttpClient {
        private final HttpClient delegate;
        private final AtomicReference<CompletableFuture<HttpResponse>> pending =
            new AtomicReference<>();
        private final AtomicReference<Thread> reader = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private StreamConnection(HttpClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public HttpResponse execute(HttpRequest request, RequestOptions options) {
            return delegate.execute(request, options);
        }

        @Override
        public CompletableFuture<HttpResponse> executeAsync(
            HttpRequest request, RequestOptions options) {
            CompletableFuture<HttpResponse> future = delegate.executeAsync(request, options);
            pending.set(future);
            // 请求在取消之后才发出时，直接取消这个 Future 才会断开底层连接。
            if (cancelled.get()) {
                future.cancel(true);
            }
            return future;
        }

        @Override
        public void close() {
            // 共享传输由 Spring Bean 销毁时关闭，单次流不能关掉连接池。
        }

        private void startReading(Runnable read) {
            Thread.ofVirtual().name("agent-model-stream").start(() -> {
                Thread worker = Thread.currentThread();
                reader.set(worker);
                if (cancelled.get()) worker.interrupt();
                read.run();
            });
        }

        private void cancel() {
            cancelled.set(true);
            CompletableFuture<HttpResponse> future = pending.get();
            if (future != null) future.cancel(true);
            // 已收到响应头时由读取线程退出并释放响应体，避免 close 等待阻塞的读取。
            Thread worker = reader.get();
            if (worker != null) worker.interrupt();
        }
    }
}
