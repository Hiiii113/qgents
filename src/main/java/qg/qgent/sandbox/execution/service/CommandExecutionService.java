package qg.qgent.sandbox.execution.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.execution.entity.ExecutionStatus;
import qg.qgent.sandbox.execution.entity.ToolExecutionEntity;
import qg.qgent.sandbox.execution.entity.ToolExecutionLogEntity;
import qg.qgent.sandbox.execution.handler.CommandRunTool;
import qg.qgent.sandbox.execution.mapper.ToolExecutionLogMapper;
import qg.qgent.sandbox.execution.mapper.ToolExecutionMapper;
import qg.qgent.sandbox.execution.output.CommandOutput;
import qg.qgent.sandbox.execution.output.CommandOutputWindow;
import qg.qgent.sandbox.filesystem.service.WorkspacePathResolver;
import qg.qgent.sandbox.metrics.SandboxMetrics;
import qg.qgent.sandbox.runtime.SandboxAllocation;
import qg.qgent.sandbox.service.SandboxService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 管理异步命令执行：登记、取消、完成通知、日志与分页输出。
 * 命令在线程池中运行；文件操作不经过本服务，直接由 SandboxFiles 调用文件处理器。
 */
@Slf4j
@Service
public class CommandExecutionService {
    private static final String COMMAND_TOOL = "command.run";
    private static final String TOOL_EXECUTION_TIMED_OUT = "TOOL_EXECUTION_TIMED_OUT";
    private static final String TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED";
    private static final String EXECUTION_QUEUE_UNAVAILABLE = "EXECUTION_QUEUE_UNAVAILABLE";
    private static final int MAX_FAILURE_REASON_LENGTH = 1024;
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");
    private static final Pattern SENSITIVE_VALUE =
        Pattern.compile(
            "(?i)\\b(token|password|secret|api[-_]?key|authorization)\\b\\s*[:=]\\s*([^\\s,;}]*)");
    private static final Pattern WINDOWS_HOST_PATH =
        Pattern.compile("(?i)(?<![A-Za-z0-9_])(?:[A-Za-z]:[\\\\/])[^\\s,;\\\"']+");
    private static final Pattern UNIX_HOST_PATH =
        Pattern.compile(
            "(?<![A-Za-z0-9_/:])/(?:home|Users|root|tmp|var|etc|opt|srv)(?:/[^\\s,;\\\"']*)?");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private final SandboxService sandboxes;
    private final WorkspacePathResolver paths;
    private final CommandRunTool commands;
    private final ToolExecutionMapper executionMapper;
    private final ToolExecutionLogMapper logMapper;
    private final ObjectMapper objectMapper;
    private final SandboxProperties properties;
    private final ExecutorService sandboxExecutionPool;
    private final Clock clock;
    private final SandboxMetrics metrics;
    private final ActiveToolExecutions active = new ActiveToolExecutions();
    private final ConcurrentMap<Long, Object> logLocks = new ConcurrentHashMap<>();

    public CommandExecutionService(
        SandboxService sandboxes,
        WorkspacePathResolver paths,
        CommandRunTool commands,
        ToolExecutionMapper executionMapper,
        ToolExecutionLogMapper logMapper,
        ObjectMapper objectMapper,
        SandboxProperties properties,
        ExecutorService sandboxExecutionPool,
        Clock clock) {
        this(
            sandboxes,
            paths,
            commands,
            executionMapper,
            logMapper,
            objectMapper,
            properties,
            sandboxExecutionPool,
            clock,
            SandboxMetrics.noop());
    }

    @Autowired
    public CommandExecutionService(
        SandboxService sandboxes,
        WorkspacePathResolver paths,
        CommandRunTool commands,
        ToolExecutionMapper executionMapper,
        ToolExecutionLogMapper logMapper,
        ObjectMapper objectMapper,
        SandboxProperties properties,
        ExecutorService sandboxExecutionPool,
        Clock clock,
        SandboxMetrics metrics) {
        this.sandboxes = sandboxes;
        this.paths = paths;
        this.commands = commands;
        this.executionMapper = executionMapper;
        this.logMapper = logMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.sandboxExecutionPool = sandboxExecutionPool;
        this.clock = clock;
        this.metrics = metrics;
        this.metrics.bindActiveCommands(active::activeCount);
    }

    /**
     * 命令参数；临时环境变量不写入数据库。
     */
    private record CommandArguments(String directory, List<String> command) {
    }

    /**
     * Sandbox 重启后把无法恢复的排队中和运行中记录标记为中断。
     */
    @PostConstruct
    void markInterruptedExecutions() {
        executionMapper.markInterrupted(properties.getInstanceId(), utc(clock.instant()));
    }

    /**
     * 提交一条命令；调用方立即拿到执行句柄，再按需等待或读取输出。
     */
    public CommandExecution start(
        UUID sandboxId,
        Long agentRunId,
        Long workspaceId,
        String directory,
        List<String> command,
        Duration timeout,
        Map<String, String> environment) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("命令不能为空");
        SandboxAllocation sandbox = sandboxes.findAllocation(sandboxId);
        String workingDirectory = directory == null || directory.isBlank() ? "." : directory;
        CommandArguments arguments = new CommandArguments(workingDirectory, command);
        ToolExecutionEntity entity = prepare(sandboxId, agentRunId, workspaceId, arguments);
        Map<String, String> credentials = environment == null ? Map.of() : environment;
        try {
            active.submit(
                entity.getId(),
                sandboxId,
                agentRunId,
                sandboxExecutionPool,
                () -> {
                    run(entity.getId(), sandbox, arguments, timeout, credentials);
                    return find(entity.getId());
                });
        } catch (RejectedExecutionException exception) {
            metrics.recordCommand("rejected", 0L);
            executionMapper.rejectQueued(
                entity.getId(),
                properties.getInstanceId(),
                EXECUTION_QUEUE_UNAVAILABLE,
                "命令执行队列不可用",
                utc(clock.instant()));
            throw new SandboxException(
                HttpStatus.SERVICE_UNAVAILABLE, EXECUTION_QUEUE_UNAVAILABLE, "命令执行队列暂时不可用");
        }
        return response(entity);
    }

    /**
     * 等待进程内完成通知；超过等待窗口返回当前状态，不反复查询数据库。
     */
    public CommandExecution await(Long executionId, Duration wait) {
        CompletableFuture<CommandExecution> completion = active.completion(executionId);
        if (completion == null || wait.isZero() || wait.isNegative()) return find(executionId);
        try {
            return completion.get(wait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            return find(executionId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待命令执行被中断", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("命令执行失败", exception.getCause());
        }
    }

    /**
     * 查询一条持久化命令执行记录。
     */
    public CommandExecution find(Long executionId) {
        return response(require(executionId));
    }

    /**
     * 取消仍在排队或运行的执行。
     * 数据库状态先切换为 CANCELLED，再中断本 Sandbox 中对应的执行线程。
     */
    public CommandExecution cancel(Long executionId) {
        LocalDateTime finishedAt = utc(clock.instant());
        if (executionMapper.markCancelled(executionId, properties.getInstanceId(), finishedAt)
            == 0) {
            ToolExecutionEntity current = require(executionId);
            if (!properties.getInstanceId().equals(current.getOwnerInstanceId())) {
                throw new SandboxException(
                    HttpStatus.CONFLICT,
                    "EXECUTION_OWNED_BY_OTHER_INSTANCE",
                    "执行不属于当前 Sandbox");
            }
            if (current.getStatus().terminal()) return response(current);
            throw new SandboxException(
                HttpStatus.CONFLICT,
                "EXECUTION_NOT_CANCELLABLE",
                "当前执行状态不可取消：" + current.getStatus());
        }
        CommandExecution cancelled = response(require(executionId));
        active.cancel(executionId, cancelled);
        metrics.recordCommand("cancelled", 0L);
        append(executionId, "SYSTEM", "工具执行已取消");
        log.debug("command cancel executionId={}", executionId);
        logLocks.remove(executionId);
        return cancelled;
    }

    /**
     * Cancels commands owned by a Sandbox and waits for their worker bodies to stop using it.
     */
    public void stopAndAwaitBySandbox(UUID sandboxId) {
        Set<Long> ids =
            new LinkedHashSet<>(
                executionMapper.selectActiveIdsBySandbox(
                    sandboxId.toString(), properties.getInstanceId()));
        ids.addAll(active.idsBySandbox(sandboxId));
        stopAndAwait(ids);
    }

    /**
     * Cancels commands started by one root or child AgentRun without affecting sibling runs.
     */
    public void stopAndAwaitByAgentRun(Long agentRunId) {
        stopAndAwait(agentRunIds(agentRunId));
    }

    /**
     * Requests cancellation for one AgentRun without waiting for its command workers to exit.
     */
    public void cancelByAgentRun(Long agentRunId) {
        agentRunIds(agentRunId).forEach(this::cancelIgnoringTerminal);
    }

    private Set<Long> agentRunIds(Long agentRunId) {
        Set<Long> ids =
            new LinkedHashSet<>(
                executionMapper.selectActiveIdsByAgentRun(
                    agentRunId, properties.getInstanceId()));
        ids.addAll(active.idsByAgentRun(agentRunId));
        return ids;
    }

    private void stopAndAwait(Collection<Long> ids) {
        ids.forEach(this::cancelIgnoringTerminal);
        active.awaitWorkers(ids);
    }

    /**
     * 记录已进入终态或已由其他实例接管时不再重复取消。
     */
    private void cancelIgnoringTerminal(Long executionId) {
        try {
            cancel(executionId);
        } catch (SandboxException exception) {
            if (!"EXECUTION_NOT_CANCELLABLE".equals(exception.getCode())) throw exception;
        }
    }

    /**
     * 读取命令当前输出；执行记录限定输出目录，不接受外部路径。
     */
    public CommandOutputWindow output(
        Long executionId, long stdoutOffset, long stderrOffset, int count) {
        ToolExecutionEntity execution = require(executionId);
        if (!COMMAND_TOOL.equals(execution.getToolName()))
            throw new SandboxException(
                HttpStatus.UNPROCESSABLE_ENTITY, "NOT_COMMAND_EXECUTION", "只有命令执行提供输出窗口");
        var sandbox = sandboxes.findAllocation(UUID.fromString(execution.getSandboxId()));
        var directory =
            CommandOutput.directory(
                paths.resolveLocal(sandbox.getWorkspaceStorageKey()), executionId);
        return new CommandOutputWindow(
            CommandOutput.read(directory, "stdout", stdoutOffset, count),
            CommandOutput.read(directory, "stderr", stderrOffset, count));
    }

    private void run(
        Long executionId,
        SandboxAllocation sandbox,
        CommandArguments arguments,
        Duration timeout,
        Map<String, String> environment) {
        Instant started = clock.instant();
        if (executionMapper.markRunning(executionId, properties.getInstanceId(), utc(started))
            == 0) {
            return;
        }
        ToolExecutionEntity running = executionMapper.selectById(executionId);
        if (running == null || running.getStatus() != ExecutionStatus.RUNNING) {
            return;
        }
        append(executionId, "SYSTEM", "开始执行工具：" + COMMAND_TOOL);
        log.info(
            "command start executionId={} sandboxId={} agentRunId={}",
            executionId,
            sandbox.getId(),
            running.getAgentRunId());

        ExecutionStatus status;
        Integer exitCode = null;
        String resultJson = null;
        String failureCode = null;
        String failureReason = null;
        try {
            Duration effective = timeout(timeout, sandbox.getExecutionTimeout());
            CommandRunTool.Outcome outcome =
                commands.run(
                    sandbox,
                    paths.resolveLocal(sandbox.getWorkspaceStorageKey()),
                    arguments.directory(),
                    arguments.command(),
                    effective,
                    environment,
                    executionId);
            exitCode = outcome.exitCode();
            resultJson = json(outcome.toMap());
            // 命令正常取得退出码即完成执行；非零退出码由模型判断，不冒充基础设施失败。
            status = ExecutionStatus.SUCCEEDED;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            status = ExecutionStatus.TIMED_OUT;
            failureCode = TOOL_EXECUTION_TIMED_OUT;
            failureReason = "工具执行超时或被中断";
            log.warn("command interrupted executionId={}", executionId);
        } catch (RuntimeException exception) {
            status = ExecutionStatus.FAILED;
            failureCode = failureCode(exception);
            failureReason = failureReason(exception);
            log.error(
                "command failed executionId={} sandboxId={} agentRunId={} failureCode={} failureReason={}",
                executionId,
                sandbox.getId(),
                running.getAgentRunId(),
                failureCode,
                failureReason);
        }

        Instant finished = clock.instant();
        executionMapper.finishIfRunning(
            executionId,
            properties.getInstanceId(),
            status,
            exitCode,
            resultJson,
            failureCode,
            failureReason,
            utc(finished));
        String commandOutcome =
            switch (status) {
                case SUCCEEDED -> "succeeded";
                case TIMED_OUT -> "timeout";
                case CANCELLED -> "cancelled";
                default -> "failed";
            };
        metrics.recordCommand(commandOutcome, Duration.between(started, finished).toNanos());
        ToolExecutionEntity completed = executionMapper.selectById(executionId);
        if (completed != null) {
            append(executionId, "SYSTEM", "工具执行结束，状态：" + completed.getStatus());
        }
        logLocks.remove(executionId);
        log.info(
            "command done executionId={} sandboxId={} agentRunId={} status={} exitCode={} durationMs={} failureCode={} failureReason={}",
            executionId,
            sandbox.getId(),
            running.getAgentRunId(),
            status,
            exitCode,
            Duration.between(started, finished).toMillis(),
            failureCode,
            failureReason);
    }

    /**
     * 先落库取得自增执行编号；异步线程结束后按该编号读取已提交记录。
     */
    private ToolExecutionEntity prepare(
        UUID sandboxId, Long agentRunId, Long workspaceId, CommandArguments arguments) {
        String argumentsJson = json(arguments);
        ToolExecutionEntity entity = new ToolExecutionEntity();
        entity.setOwnerInstanceId(properties.getInstanceId());
        entity.setSandboxId(sandboxId.toString());
        entity.setAgentRunId(agentRunId);
        entity.setWorkspaceId(workspaceId);
        entity.setToolName(COMMAND_TOOL);
        entity.setArgumentsJson(argumentsJson);
        entity.setStatus(ExecutionStatus.QUEUED);
        entity.setCreatedAt(utc(clock.instant()));
        executionMapper.insert(entity);
        append(entity.getId(), "SYSTEM", "工具执行已登记：" + COMMAND_TOOL);
        log.info(
            "command registered executionId={} sandboxId={} agentRunId={}",
            entity.getId(),
            sandboxId,
            agentRunId);
        return entity;
    }

    private ToolExecutionEntity require(Long executionId) {
        ToolExecutionEntity entity = executionMapper.selectById(executionId);
        if (entity == null) {
            throw new SandboxException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "工具执行记录不存在");
        }
        return entity;
    }

    private void append(Long executionId, String stream, String content) {
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

    private Duration timeout(Duration requested, Duration sandboxLimit) {
        Duration effective =
            requested == null || requested.compareTo(sandboxLimit) > 0 ? sandboxLimit : requested;
        return effective.compareTo(properties.getMaxExecutionTimeout()) <= 0
            ? effective
            : properties.getMaxExecutionTimeout();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new SandboxException(
                HttpStatus.UNPROCESSABLE_ENTITY, "TOOL_ARGUMENT_INVALID", "命令参数无法序列化");
        }
    }

    private Map<String, Object> map(String value) {
        if (value == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            // 结果由本服务写入，读不回来说明数据已损坏，不能当空结果交给模型。
            throw new IllegalStateException("命令执行结果无法解析", exception);
        }
    }

    private String failureCode(RuntimeException exception) {
        if (exception instanceof SandboxException sandboxException) {
            return sandboxException.getCode();
        }
        if (exception instanceof IllegalArgumentException) return "TOOL_ARGUMENT_INVALID";
        return TOOL_EXECUTION_FAILED;
    }

    private String failureReason(RuntimeException exception) {
        if ((exception instanceof SandboxException || exception instanceof IllegalArgumentException)
            && exception.getMessage() != null
            && !exception.getMessage().isBlank()) {
            return sanitizeFailureReason(exception.getMessage());
        }
        return "工具执行失败";
    }

    /**
     * SandboxException 的说明可能来自底层文件或运行时组件，只保留有限、可展示的摘要。
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
            ? sanitized
            : sanitized.substring(0, MAX_FAILURE_REASON_LENGTH - 3) + "...";
    }

    private CommandExecution response(ToolExecutionEntity entity) {
        return new CommandExecution(
            entity.getId(),
            UUID.fromString(entity.getSandboxId()),
            entity.getToolName(),
            entity.getStatus(),
            entity.getExitCode(),
            map(entity.getResultJson()),
            entity.getFailureCode(),
            entity.getFailureReason());
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
