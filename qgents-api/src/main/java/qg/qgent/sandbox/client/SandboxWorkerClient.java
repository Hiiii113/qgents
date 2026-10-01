package qg.qgent.sandbox.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import qg.qgent.common.config.PerformanceMetrics;
import qg.qgent.common.web.ApiException;
import qg.qgent.sandbox.dto.WorkerCreateSandboxRequest;
import qg.qgent.sandbox.dto.WorkerErrorResponse;
import qg.qgent.sandbox.dto.WorkerSandbox;
import qg.qgent.sandbox.dto.WorkerToolExecution;
import qg.qgent.sandbox.dto.WorkerToolExecutionRequest;
import qg.qgent.sandbox.dto.WorkerWorkspace;
import qg.qgent.sandbox.dto.WorkerWorkspaceProvisionRequest;
import qg.qgent.sandbox.service.ExecutionContentSanitizer;
import qg.qgent.workspace.util.Sha256;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 后端2 Sandbox Worker（{@code /internal/v1}）的受控 HTTP 客户端。
 * <p>
 * 只做协议调用与错误映射，不含任何业务编排：Workspace 准备、Sandbox 生命周期与工具执行
 * 均由上层（会话管理器 / 端口实现）按序驱动。所有方法：
 * <ul>
 *   <li>传输层失败（连接失败、超时）统一映射为 {@code SANDBOX_WORKER_UNAVAILABLE}（502）；</li>
 *   <li>Worker 返回的非 2xx 响应解析 {@code {code,message}}，保留 Worker 的业务错误码，
 *       供端口区分工具级失败（可回灌 LLM 纠正）与基础设施失败。</li>
 * </ul>
 * 工具临时环境仅用于执行，不保存为工具参数。
 */
@Slf4j
public class SandboxWorkerClient {

    private static final String BASE_PATH = "/internal/v1";
    private static final String WORKSPACES = BASE_PATH + "/workspaces/{workspaceId}";
    private static final String SANDBOXES = BASE_PATH + "/sandboxes";
    private static final String SANDBOX = BASE_PATH + "/sandboxes/{sandboxId}";
    private static final String SANDBOX_LEASE_RENEW = SANDBOX + "/lease/renew";
    private static final String TOOL_EXECUTIONS = BASE_PATH + "/sandboxes/{sandboxId}/tool-executions";
    private static final String TOOL_EXECUTION = BASE_PATH + "/tool-executions/{executionId}";

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final PerformanceMetrics metrics;
    public SandboxWorkerClient(RestClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, null);
    }

    public SandboxWorkerClient(RestClient client, ObjectMapper objectMapper, PerformanceMetrics metrics) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * 幂等准备空工作区并返回 storageKey。
     */
    public WorkerWorkspace provisionWorkspace(UUID workspaceId, WorkerWorkspaceProvisionRequest request) {
        return execute(() -> client.put()
                .uri(WORKSPACES, workspaceId)
                .body(request)
                .retrieve()
                .body(WorkerWorkspace.class));
    }

    /**
     * 查询工作区状态。
     */
    public WorkerWorkspace getWorkspace(UUID workspaceId) {
        return execute(() -> client.get()
                .uri(WORKSPACES, workspaceId)
                .retrieve()
                .body(WorkerWorkspace.class));
    }

    /**
     * 创建 Sandbox 并应用 Worker 本地资源上限。
     */
    public WorkerSandbox createSandbox(WorkerCreateSandboxRequest request) {
        return execute(() -> client.post()
                .uri(SANDBOXES)
                .body(request)
                .retrieve()
                .body(WorkerSandbox.class));
    }

    /**
     * 查询 Sandbox 当前状态与租约期限。
     */
    public WorkerSandbox getSandbox(UUID sandboxId) {
        return execute(() -> client.get()
                .uri(SANDBOX, sandboxId)
                .retrieve()
                .body(WorkerSandbox.class));
    }

    /**
     * 延长 Sandbox 空闲租约；ttlSeconds 为空时使用 Worker 本地默认配置。
     */
    public WorkerSandbox renewSandbox(UUID sandboxId, Long ttlSeconds) {
        return execute(() -> client.post()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path(SANDBOX_LEASE_RENEW);
                    if (ttlSeconds != null) {
                        builder.queryParam("ttlSeconds", ttlSeconds);
                    }
                    return builder.build(sandboxId);
                })
                .retrieve()
                .body(WorkerSandbox.class));
    }

    /**
     * 延长 Sandbox 空闲租约，使用 Worker 本地默认配置。
     */
    public WorkerSandbox renewSandbox(UUID sandboxId) {
        return renewSandbox(sandboxId, null);
    }

    /**
     * 取消活跃工具执行并销毁临时容器；持久 Workspace 不随 Sandbox 删除。
     */
    public void destroySandbox(UUID sandboxId) {
        execute(() -> {
            client.delete().uri(SANDBOX, sandboxId).retrieve().toBodilessEntity();
            return null;
        });
    }

    /**
     * 提交结构化工具执行，返回 202 入队结果。
     */
    public WorkerToolExecution submitToolExecution(UUID sandboxId, WorkerToolExecutionRequest request) {
        return execute(() -> client.post()
                .uri(TOOL_EXECUTIONS, sandboxId)
                .body(request)
                .retrieve()
                .body(WorkerToolExecution.class));
    }

    /**
     * 查询持久化工具执行记录与终态结果。
     */
    public WorkerToolExecution getToolExecution(UUID executionId) {
        return execute(() -> client.get()
                .uri(TOOL_EXECUTION, executionId)
                .retrieve()
                .body(WorkerToolExecution.class));
    }

    /**
     * 统一执行调用并做错误映射，不让 RestClient 原始异常泄漏到上层。
     */
    private <T> T execute(Supplier<T> call) {
        Timer.Sample timer = metrics == null ? null : metrics.start();
        try {
            T result = call.get();
            if (metrics != null) {
                metrics.stop(timer, "qgents.worker.request.duration", "worker_http", "succeeded");
                metrics.increment("qgents.worker.request.total", "worker_http", "succeeded");
            }
            return result;
        } catch (RestClientResponseException exception) {
            if (metrics != null) {
                metrics.stop(timer, "qgents.worker.request.duration", "worker_http", "failed");
                metrics.increment("qgents.worker.request.total", "worker_http", "failed");
            }
            throw workerError(exception);
        } catch (RestClientException exception) {
            if (metrics != null) {
                metrics.stop(timer, "qgents.worker.request.duration", "worker_http", "failed");
                metrics.increment("qgents.worker.request.total", "worker_http", "failed");
            }
            SandboxWorkerTransportException failure = transportFailure(exception);
            log.warn("sandbox worker transport failed diagnosticCode={} exceptionType={}",
                    failure.diagnosticCode(), rootCause(exception).getClass().getSimpleName());
            throw failure;
        }
    }

    /**
     * 把 Worker 非 2xx 响应映射为携带业务错误码的 ApiException；解析失败时退回通用错误。
     */
    private ApiException workerError(RestClientResponseException exception) {
        String code = "SANDBOX_WORKER_ERROR";
        String message = "Sandbox Worker 返回了无法处理的错误响应";
        String body = null;
        try {
            body = exception.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                WorkerErrorResponse error = objectMapper.readValue(body, WorkerErrorResponse.class);
                if (error != null) {
                    if (error.getCode() != null && !error.getCode().isBlank()) {
                        code = error.getCode();
                    }
                    if (error.getMessage() != null && !error.getMessage().isBlank()) {
                        message = error.getMessage();
                    }
                }
            }
        } catch (Exception ignored) {
            // 错误体非预期结构时退回通用错误码与安全消息。
        }
        log.warn("sandbox worker returned non-2xx status={} statusText={} responseBytes={} responseSha256={} code={}",
                exception.getStatusCode().value(), exception.getStatusText(), utf8Bytes(body),
                Sha256.hex((body == null ? "" : body).getBytes(StandardCharsets.UTF_8)), code);
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        return new ApiException(status == null ? HttpStatus.BAD_GATEWAY : status, code,
                safeDiagnosticMessage(message));
    }

    private static int utf8Bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    static SandboxWorkerTransportException transportFailure(RestClientException exception) {
        Throwable root = rootCause(exception);
        String diagnosticCode;
        String message;
        if (root instanceof UnknownHostException) {
            diagnosticCode = "WORKER_DNS_FAILED";
            message = "无法解析 Sandbox Worker 服务地址";
        } else if (root instanceof ConnectException) {
            diagnosticCode = "WORKER_CONNECTION_REFUSED";
            message = "Sandbox Worker 拒绝连接";
        } else if (root instanceof NoRouteToHostException) {
            diagnosticCode = "WORKER_NETWORK_UNREACHABLE";
            message = "无法到达 Sandbox Worker 所在网络";
        } else if (root instanceof SocketTimeoutException) {
            diagnosticCode = "WORKER_RESPONSE_TIMEOUT";
            message = "等待 Sandbox Worker 响应超时";
        } else {
            diagnosticCode = "WORKER_TRANSPORT_FAILED";
            message = "与 Sandbox Worker 的网络通信失败";
        }
        return new SandboxWorkerTransportException(diagnosticCode, message);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeDiagnosticMessage(String message) {
        String sanitized = ExecutionContentSanitizer.sanitizeDiagnosticDetail(message);
        if (sanitized == null || sanitized.isBlank()) {
            return "Sandbox Worker 返回了无法处理的错误响应";
        }
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }
}
