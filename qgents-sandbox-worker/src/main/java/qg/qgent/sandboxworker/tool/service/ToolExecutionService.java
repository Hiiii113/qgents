package qg.qgent.sandboxworker.tool.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import qg.qgent.sandboxworker.common.web.WorkerException;
import qg.qgent.sandboxworker.sandbox.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.sandbox.runtime.SandboxAllocation;
import qg.qgent.sandboxworker.sandbox.service.SandboxService;
import qg.qgent.sandboxworker.tool.dto.ExecutionLogEntryResponse;
import qg.qgent.sandboxworker.tool.dto.ExecutionLogsResponse;
import qg.qgent.sandboxworker.tool.dto.ToolContext;
import qg.qgent.sandboxworker.tool.dto.ToolExecutionRequest;
import qg.qgent.sandboxworker.tool.dto.ToolExecutionResponse;
import qg.qgent.sandboxworker.tool.dto.ToolResult;
import qg.qgent.sandboxworker.tool.entity.ToolExecutionEntity;
import qg.qgent.sandboxworker.tool.entity.ToolExecutionLogEntity;
import qg.qgent.sandboxworker.tool.mapper.ToolExecutionLogMapper;
import qg.qgent.sandboxworker.tool.mapper.ToolExecutionMapper;
import qg.qgent.sandboxworker.workspace.service.WorkspacePathResolver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/**
 * 统一管理结构化工具的异步执行、取消、日志和 MySQL 持久化。
 * 提交接口只负责建立 QUEUED 记录，实际工具在 Worker 固定线程池中运行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutionService {
    private static final String PROCESS_EXIT_NONZERO = "PROCESS_EXIT_NONZERO";
    private static final String TOOL_EXECUTION_TIMED_OUT = "TOOL_EXECUTION_TIMED_OUT";
    private static final String TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED";
    private static final String EXECUTION_QUEUE_UNAVAILABLE = "EXECUTION_QUEUE_UNAVAILABLE";
    private static final int MAX_FAILURE_REASON_LENGTH = 1024;
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)\\b(token|password|secret|api[-_]?key|authorization)\\b\\s*[:=]\\s*([^\\s,;}]*)");
    private static final Pattern WINDOWS_HOST_PATH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:[A-Za-z]:[\\\\/])[^\\s,;\\\"']+");
    private static final Pattern UNIX_HOST_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_/:])/(?:home|Users|root|tmp|var|etc|opt|srv)(?:/[^\\s,;\\\"']*)?");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private final SandboxService sandboxes;
    private final WorkspacePathResolver paths;
    private final ToolRegistry tools;
    private final ToolExecutionMapper executionMapper;
    private final ToolExecutionLogMapper logMapper;
    private final ObjectMapper objectMapper;
    private final SandboxWorkerProperties properties;
    private final ExecutorService sandboxExecutionPool;
    private final Clock clock;
    private final ConcurrentMap<UUID, Thread> activeThreads = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> logLocks = new ConcurrentHashMap<>();

    /**
     * Worker 重启后把无法恢复的排队中和运行中记录标记为中断。
     */
    @PostConstruct
    void markInterruptedExecutions() {
        executionMapper.markInterrupted(properties.getWorkerId(), utc(clock.instant()));
    }

    /**
     * 创建持久化执行记录并投递后台线程。
     * executionId 是执行资源编号，重复使用时返回冲突，不提供请求重放语义。
     */
    public ToolExecutionResponse submit(UUID sandboxId, ToolExecutionRequest request) {
        // executionID 不能重复
        if (executionMapper.selectById(request.getExecutionId().toString()) != null) {
            throw new WorkerException(HttpStatus.CONFLICT,
                    "EXECUTION_ID_CONFLICT", "执行编号已经存在");
        }

        // 沙箱需要存在
        SandboxAllocation sandbox = sandboxes.findAllocation(sandboxId);
        // 参数与执行结果持久化，临时环境变量只用于本次进程
        String argumentsJson = json(request.getArguments());
        ToolExecutionEntity entity = createEntity(sandboxId, request, argumentsJson);
        try {
            executionMapper.insert(entity);
        } catch (RuntimeException exception) {
            if (executionMapper.selectById(entity.getId()) != null) {
                throw new WorkerException(HttpStatus.CONFLICT,
                        "EXECUTION_ID_CONFLICT", "执行编号已经存在");
            }
            throw exception;
        }
        append(entity.getId(), "SYSTEM", "工具执行已进入队列：" + request.getTool());
        log.info("tool queued executionId={} sandboxId={} agentRunId={} tool={}",
                entity.getId(), sandboxId, sandbox.getAgentRunId(), request.getTool());
        // 投给后台开始跑
        try {
            sandboxExecutionPool.execute(() -> run(entity.getId(), sandbox, request));
        } catch (RejectedExecutionException exception) {
            executionMapper.rejectQueued(entity.getId(), properties.getWorkerId(),
                    EXECUTION_QUEUE_UNAVAILABLE, "Worker 执行队列不可用", utc(clock.instant()));
            throw new WorkerException(HttpStatus.SERVICE_UNAVAILABLE,
                    "EXECUTION_QUEUE_UNAVAILABLE", "Worker 执行队列暂时不可用");
        }
        return response(entity);
    }

    /**
     * 查询一条持久化工具执行记录。
     */
    public ToolExecutionResponse find(UUID executionId) {
        return response(require(executionId));
    }

    /**
     * 取消仍在排队或运行的执行。
     * 数据库状态先切换为 CANCELLED，再中断本 Worker 中对应的执行线程。
     */
    public ToolExecutionResponse cancel(UUID executionId) {
        LocalDateTime finishedAt = utc(clock.instant());
        if (executionMapper.markCancelled(executionId.toString(), properties.getWorkerId(), finishedAt) == 0) {
            ToolExecutionEntity current = require(executionId);
            if (!properties.getWorkerId().equals(current.getOwnerWorkerId())) {
                throw new WorkerException(HttpStatus.CONFLICT,
                        "EXECUTION_OWNED_BY_OTHER_WORKER", "执行不属于当前 Worker");
            }
            throw new WorkerException(HttpStatus.CONFLICT,
                    "EXECUTION_NOT_CANCELLABLE", "当前执行状态不可取消：" + current.getStatus());
        }
        Thread thread = activeThreads.get(executionId);
        if (thread != null) {
            thread.interrupt();
        }
        append(executionId.toString(), "SYSTEM", "工具执行已取消");
        log.debug("tool cancel executionId={}", executionId);
        logLocks.remove(executionId.toString());
        return response(require(executionId));
    }

    /**
     * 取消指定 Sandbox 中仍处于活动状态的全部工具执行。
     */
    public void cancelBySandbox(UUID sandboxId) {
        executionMapper.selectActiveIdsBySandbox(sandboxId.toString(), properties.getWorkerId()).forEach(id -> {
            try {
                cancel(UUID.fromString(id));
            } catch (WorkerException exception) {
                if (!"EXECUTION_NOT_CANCELLABLE".equals(exception.getCode())) {
                    throw exception;
                }
            }
        });
    }

    /** 读取命令当前输出；执行记录限定输出目录，不接受外部路径。 */
    public Map<String, Object> output(UUID executionId, long stdoutOffset, long stderrOffset, int count) {
        ToolExecutionEntity execution = require(executionId);
        if (!"command.run".equals(execution.getToolName())) throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY,
                "NOT_COMMAND_EXECUTION", "只有命令执行提供输出窗口");
        var sandbox = sandboxes.findAllocation(UUID.fromString(execution.getSandboxId()));
        var directory = qg.qgent.sandboxworker.tool.handler.CommandOutput.directory(
                paths.resolveLocal(sandbox.getWorkspaceStorageKey()), executionId);
        return Map.of("stdout", qg.qgent.sandboxworker.tool.handler.CommandOutput.read(directory, "stdout", stdoutOffset, count),
                "stderr", qg.qgent.sandboxworker.tool.handler.CommandOutput.read(directory, "stderr", stderrOffset, count));
    }

    /** 按执行内递增序号分页读取日志。 */
    public ExecutionLogsResponse logs(UUID executionId, long after, int limit) {
        require(executionId);
        List<ExecutionLogEntryResponse> items = logMapper
                .selectAfter(executionId.toString(), after, Math.max(1, Math.min(limit, 1000)))
                .stream()
                .map(row -> new ExecutionLogEntryResponse(row.getSequenceNo(), row.getStream(), row.getContent(),
                        row.getCreatedAt().toInstant(ZoneOffset.UTC)))
                .toList();
        long cursor = items.isEmpty() ? after : items.get(items.size() - 1).getSequence();
        return new ExecutionLogsResponse(items, cursor);
    }

    private void run(String executionId, SandboxAllocation sandbox, ToolExecutionRequest request) {
        Instant started = clock.instant();
        if (executionMapper.markRunning(executionId, properties.getWorkerId(), utc(started)) == 0) {
            return;
        }
        UUID id = UUID.fromString(executionId);
        activeThreads.put(id, Thread.currentThread());
        ToolExecutionEntity running = executionMapper.selectById(executionId);
        if (running == null || !"RUNNING".equals(running.getStatus())) {
            activeThreads.remove(id, Thread.currentThread());
            return;
        }
        append(executionId, "SYSTEM", "开始执行工具：" + request.getTool());
        log.info("tool start executionId={} sandboxId={} agentRunId={} tool={}",
                executionId, sandbox.getId(), sandbox.getAgentRunId(), request.getTool());

        String status;
        Integer exitCode = null;
        String resultJson = null;
        String failureCode = null;
        String failureReason = null;
        try {
            Duration timeout = timeout(request.getTimeoutSeconds(), sandbox.getExecutionTimeout());
            ToolContext context = context(sandbox, timeout);
            context.setExecutionId(id);
            context.setEnvironment(request.getEnvironment() == null ? Map.of() : request.getEnvironment());
            ToolResult result = tools.execute(request.getTool(), context, request.getArguments());
            result.getStandardOutput().forEach(line -> append(executionId, "STDOUT", line));
            result.getStandardError().forEach(line -> append(executionId, "STDERR", line));
            exitCode = result.getExitCode();
            resultJson = json(result.getResult());
            // 命令正常取得退出码即完成执行；非零退出码由模型判断，不冒充基础设施失败。
            status = "command.run".equals(request.getTool()) || exitCode == null || exitCode == 0 ? "SUCCEEDED" : "FAILED";
            if ("FAILED".equals(status)) {
                failureCode = PROCESS_EXIT_NONZERO;
                failureReason = "工具进程以非零退出码结束";
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            status = "TIMED_OUT";
            failureCode = TOOL_EXECUTION_TIMED_OUT;
            failureReason = "工具执行超时或被中断";
            log.warn("tool interrupted executionId={} tool={}", executionId, request.getTool());
        } catch (RuntimeException exception) {
            status = "FAILED";
            failureCode = failureCode(exception);
            failureReason = failureReason(exception);
            log.error("tool failed executionId={} sandboxId={} agentRunId={} tool={} failureCode={} failureReason={}",
                    executionId, sandbox.getId(), sandbox.getAgentRunId(), request.getTool(), failureCode, failureReason);
        } finally {
            activeThreads.remove(id, Thread.currentThread());
        }

        executionMapper.finishIfRunning(executionId, properties.getWorkerId(), status,
                exitCode, resultJson, failureCode, failureReason,
                utc(clock.instant()));
        ToolExecutionEntity completed = executionMapper.selectById(executionId);
        if (completed != null) {
            append(executionId, "SYSTEM", "工具执行结束，状态：" + completed.getStatus());
        }
        logLocks.remove(executionId);
        log.info("tool done executionId={} sandboxId={} agentRunId={} tool={} status={} exitCode={} durationMs={} failureCode={} failureReason={}",
                executionId, sandbox.getId(), sandbox.getAgentRunId(), request.getTool(), status, exitCode,
                Duration.between(started, clock.instant()).toMillis(), failureCode, failureReason);
    }

    private ToolContext context(SandboxAllocation sandbox, Duration timeout) {
        return new ToolContext(sandbox, paths.resolveLocal(sandbox.getWorkspaceStorageKey()), "/workspace", timeout);
    }

    private ToolExecutionEntity createEntity(UUID sandboxId, ToolExecutionRequest request, String argumentsJson) {
        ToolExecutionEntity entity = new ToolExecutionEntity();
        entity.setId(request.getExecutionId().toString());
        entity.setOwnerWorkerId(properties.getWorkerId());
        entity.setSandboxId(sandboxId.toString());
        entity.setToolName(request.getTool());
        entity.setArgumentsJson(argumentsJson);
        entity.setStatus("QUEUED");
        entity.setCreatedAt(utc(clock.instant()));
        return entity;
    }

    private ToolExecutionEntity require(UUID executionId) {
        ToolExecutionEntity entity = executionMapper.selectById(executionId.toString());
        if (entity == null) {
            throw new WorkerException(HttpStatus.NOT_FOUND,
                    "EXECUTION_NOT_FOUND", "工具执行记录不存在");
        }
        return entity;
    }

    private void append(String executionId, String stream, String content) {
        Object lock = logLocks.computeIfAbsent(executionId, ignored -> new Object());
        synchronized (lock) {
            ToolExecutionLogEntity log = new ToolExecutionLogEntity();
            log.setExecutionId(executionId);
            log.setSequenceNo(logMapper.selectMaxSequence(executionId) + 1);
            log.setStream(stream);
            log.setContent(content.length() <= 16000 ? content : content.substring(0, 16000));
            log.setCreatedAt(utc(clock.instant()));
            logMapper.insert(log);
        }
    }

    private Duration timeout(Long seconds, Duration sandboxLimit) {
        Duration requested = seconds == null ? sandboxLimit : Duration.ofSeconds(seconds);
        Duration effective = requested.compareTo(sandboxLimit) <= 0 ? requested : sandboxLimit;
        return effective.compareTo(properties.getMaxExecutionTimeout()) <= 0
                ? effective
                : properties.getMaxExecutionTimeout();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "TOOL_ARGUMENT_INVALID", "工具参数无法序列化");
        }
    }

    private Map<String, Object> map(String value) {
        if (value == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            return Map.of("raw", value);
        }
    }

    private String failureCode(RuntimeException exception) {
        if (exception instanceof WorkerException workerException) {
            return workerException.getCode();
        }
        if (exception instanceof IllegalArgumentException) return "TOOL_ARGUMENT_INVALID";
        return TOOL_EXECUTION_FAILED;
    }

    private String failureReason(RuntimeException exception) {
        if ((exception instanceof WorkerException || exception instanceof IllegalArgumentException)
                && exception.getMessage() != null && !exception.getMessage().isBlank()) {
            return sanitizeFailureReason(exception.getMessage());
        }
        return "工具执行失败";
    }

    /**
     * WorkerException 的说明可能来自底层文件或运行时组件，只保留有限、可展示的摘要。
     * 不允许将凭据、宿主机路径或多行内部细节写入执行记录。
     */
    private String sanitizeFailureReason(String value) {
        String sanitized = BEARER.matcher(value).replaceAll("Bearer [redacted]");
        sanitized = SENSITIVE_VALUE.matcher(sanitized).replaceAll("$1=[redacted]");
        sanitized = WINDOWS_HOST_PATH.matcher(sanitized).replaceAll("[host path omitted]");
        sanitized = UNIX_HOST_PATH.matcher(sanitized).replaceAll("[host path omitted]");
        sanitized = sanitized.replaceAll("[\\r\\n]+", " ").strip();
        if (sanitized.isBlank()) {
            return "工具执行失败";
        }
        return sanitized.length() <= MAX_FAILURE_REASON_LENGTH
                ? sanitized : sanitized.substring(0, MAX_FAILURE_REASON_LENGTH - 3) + "...";
    }

    private ToolExecutionResponse response(ToolExecutionEntity entity) {
        return new ToolExecutionResponse(UUID.fromString(entity.getId()), entity.getOwnerWorkerId(),
                UUID.fromString(entity.getSandboxId()),
                entity.getToolName(), entity.getStatus(), entity.getExitCode(), map(entity.getResultJson()),
                entity.getFailureCode(), entity.getFailureReason(), instant(entity.getCreatedAt()), instant(entity.getStartedAt()),
                instant(entity.getFinishedAt()));
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
