package qg.qgent.sandbox.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.runtime.SandboxAllocation;
import qg.qgent.sandbox.service.SandboxService;
import qg.qgent.sandbox.execution.dto.ToolExecutionRequest;
import qg.qgent.sandbox.execution.dto.ToolResult;
import qg.qgent.sandbox.execution.entity.ToolExecutionEntity;
import qg.qgent.sandbox.execution.entity.ToolExecutionLogEntity;
import qg.qgent.sandbox.execution.mapper.ToolExecutionLogMapper;
import qg.qgent.sandbox.execution.mapper.ToolExecutionMapper;
import qg.qgent.sandbox.filesystem.service.WorkspacePathResolver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.MDC;
import qg.qgent.sandbox.dto.SandboxExecutionContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;

class ToolExecutionServiceTest {
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
        MDC.clear();
        SandboxExecutionContext.set(null);
    }

    @Test
    void fileToolRunsOnCallerEvenWhenCommandPoolIsBusy() throws Exception {
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1);
        pool.execute(() -> {
            busy.countDown();
            try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        assertTrue(busy.await(5, TimeUnit.SECONDS));
        ToolExecutionFixture fixture = fixture();
        AtomicReference<Thread> toolThread = new AtomicReference<>();
        UUID child = UUID.randomUUID(), workspace = UUID.randomUUID();
        fixture.request().setAgentRunId(child);
        fixture.request().setWorkspaceId(workspace);
        when(fixture.tools().execute(anyString(), any(), any())).thenAnswer(invocation -> {
            toolThread.set(Thread.currentThread());
            return ToolResult.value(Map.of("lines", List.of("content")));
        });
        try {
            var result = fixture.service().execute(fixture.sandboxId(), fixture.request());
            assertEquals("SUCCEEDED", result.getStatus());
            assertEquals(Thread.currentThread(), toolThread.get());
            assertEquals(child.toString(), fixture.rows().get(fixture.executionId().toString()).getAgentRunId());
            assertEquals(workspace.toString(), fixture.rows().get(fixture.executionId().toString()).getWorkspaceId());
        } finally { release.countDown(); }
    }

    @Test
    void commandWaitUsesCompletionNotificationAndPreservesContext() throws Exception {
        ToolExecutionFixture fixture = fixture();
        fixture.request().setTool("command.run");
        UUID child = UUID.randomUUID();
        fixture.request().setAgentRunId(child);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<UUID> observedRun = new AtomicReference<>();
        AtomicReference<String> observedRequest = new AtomicReference<>();
        when(fixture.tools().execute(anyString(), any(), any())).thenAnswer(invocation -> {
            observedRun.set(SandboxExecutionContext.currentAgentRunId());
            observedRequest.set(MDC.get("requestId"));
            started.countDown();
            release.await();
            return new ToolResult(7, Map.of("stdout", "done"), List.of(), List.of());
        });
        MDC.put("requestId", "request-for-command");
        fixture.service().submit(fixture.sandboxId(), fixture.request());
        assertTrue(started.await(5, TimeUnit.SECONDS));
        clearInvocations(fixture.mapper());
        try (var waiter = Executors.newSingleThreadExecutor()) {
            var result = waiter.submit(() -> fixture.service().await(fixture.executionId(), Duration.ofSeconds(5)));
            release.countDown();
            assertEquals(7, result.get(5, TimeUnit.SECONDS).getExitCode());
        } finally { release.countDown(); }
        assertEquals(child, observedRun.get());
        assertEquals("request-for-command", observedRequest.get());
        verify(fixture.mapper(), atMost(4)).selectById(fixture.executionId().toString());
    }

    @Test
    void shortWaitReturnsHandleAndCancellationWakesWaiter() throws Exception {
        ToolExecutionFixture fixture = fixture();
        fixture.request().setTool("command.run");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        when(fixture.tools().execute(anyString(), any(), any())).thenAnswer(invocation -> {
            started.countDown(); release.await();
            return ToolResult.value(Map.of());
        });
        fixture.service().submit(fixture.sandboxId(), fixture.request());
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals("RUNNING", fixture.service().await(fixture.executionId(), Duration.ofMillis(20)).getStatus());
        try (var waiter = Executors.newSingleThreadExecutor()) {
            var result = waiter.submit(() -> fixture.service().await(fixture.executionId(), Duration.ofSeconds(5)));
            assertEquals("CANCELLED", fixture.service().cancel(fixture.executionId()).getStatus());
            assertEquals("CANCELLED", result.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals("CANCELLED", fixture.service().cancel(fixture.executionId()).getStatus());
        } finally { release.countDown(); }
    }

    @Test
    void submitPersistsQueuedRecordAndCompletesInBackground() throws Exception {
        Map<String, ToolExecutionEntity> rows = new ConcurrentHashMap<>();
        ArrayList<ToolExecutionLogEntity> logs = new ArrayList<>();
        ToolExecutionMapper executions = executionMapper(rows);
        ToolExecutionLogMapper logMapper = logMapper(logs);
        SandboxService sandboxes = mock(SandboxService.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        SandboxProperties properties = new SandboxProperties();
        UUID sandboxId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        SandboxAllocation sandbox = allocation(sandboxId);
        when(sandboxes.findAllocation(sandboxId)).thenReturn(sandbox);
        when(tools.execute(anyString(), any(), any())).thenReturn(ToolResult.value(Map.of("ok", true)));
        ToolExecutionService service = new ToolExecutionService(sandboxes, paths, tools, executions, logMapper,
                new ObjectMapper().findAndRegisterModules(), properties, pool, Clock.systemUTC());
        ToolExecutionRequest request = new ToolExecutionRequest();
        request.setExecutionId(executionId);
        request.setTool("file.read");
        request.setEnvironment(Map.of("GIT_CONFIG_VALUE_0", "temporary-secret"));

        String submitted = service.submit(sandboxId, request).getStatus();
        assertTrue(java.util.List.of("QUEUED", "RUNNING", "SUCCEEDED").contains(submitted));
        for (int attempt = 0; attempt < 100 && !"SUCCEEDED".equals(rows.get(executionId.toString()).getStatus()); attempt++) {
            Thread.sleep(10);
        }

        assertEquals("SUCCEEDED", service.find(executionId).getStatus());
        assertFalse(rows.get(executionId.toString()).getArgumentsJson().contains("temporary-secret"));
        org.mockito.Mockito.verify(tools).execute(anyString(), org.mockito.ArgumentMatchers.argThat(context ->
                "temporary-secret".equals(context.getEnvironment().get("GIT_CONFIG_VALUE_0"))), any());
        assertEquals(null, service.find(executionId).getFailureCode());
        assertEquals(null, service.find(executionId).getFailureReason());
        assertFalse(logs.isEmpty());
    }

    @Test
    void sandboxExceptionPersistsStructuredFailureCodeAndReason() throws Exception {
        ToolExecutionFixture fixture = fixture();
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenThrow(new SandboxException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "FILE_PATCH_FAILED", "hunk 声明行数与正文不一致"));

        fixture.service().submit(fixture.sandboxId(), fixture.request());
        awaitTerminal(fixture.rows(), fixture.executionId());

        assertEquals("FAILED", fixture.service().find(fixture.executionId()).getStatus());
        assertEquals("FILE_PATCH_FAILED", fixture.service().find(fixture.executionId()).getFailureCode());
        assertEquals("hunk 声明行数与正文不一致", fixture.service().find(fixture.executionId()).getFailureReason());
    }

    @Test
    void sandboxExceptionFailureReasonIsRedactedAndBounded() throws Exception {
        ToolExecutionFixture fixture = fixture();
        String detail = "Bearer super-secret token=abc https://example.invalid/test?api_key=abc "
                + "C:\\Users\\Administrator\\private.txt " + "x".repeat(1200);
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenThrow(new SandboxException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "TOOL_PATH_INVALID", detail));

        fixture.service().submit(fixture.sandboxId(), fixture.request());
        awaitTerminal(fixture.rows(), fixture.executionId());

        String reason = fixture.service().find(fixture.executionId()).getFailureReason();
        assertEquals("TOOL_PATH_INVALID", fixture.service().find(fixture.executionId()).getFailureCode());
        assertFalse(reason.contains("super-secret"));
        assertFalse(reason.contains("token=abc"));
        assertFalse(reason.contains("C:\\Users\\Administrator"));
        assertTrue(reason.contains("https://example.invalid/test?api_key=[redacted]"));
        assertTrue(reason.contains("[redacted]"));
        assertTrue(reason.contains("[host path omitted]"));
        assertTrue(reason.length() <= 1024);
    }

    @Test
    void processExitFailurePersistsStableCodeWithoutOutput() throws Exception {
        ToolExecutionFixture fixture = fixture();
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenReturn(new ToolResult(17, Map.of("command", "mvn"), List.of("secret output"), List.of()));

        fixture.service().submit(fixture.sandboxId(), fixture.request());
        awaitTerminal(fixture.rows(), fixture.executionId());

        assertEquals("PROCESS_EXIT_NONZERO", fixture.service().find(fixture.executionId()).getFailureCode());
        assertEquals("工具进程以非零退出码结束", fixture.service().find(fixture.executionId()).getFailureReason());
        assertEquals(17, fixture.service().find(fixture.executionId()).getExitCode());
    }

    @Test
    void nonzeroCommandExitIsACompletedResult() throws Exception {
        ToolExecutionFixture fixture = fixture();
        fixture.request().setTool("command.run");
        when(fixture.tools().execute(anyString(), any(), any())).thenReturn(new ToolResult(7, Map.of("stdout", "diagnostic"), List.of(), List.of()));
        fixture.service().submit(fixture.sandboxId(), fixture.request());
        awaitTerminal(fixture.rows(), fixture.executionId());
        assertEquals("SUCCEEDED", fixture.service().find(fixture.executionId()).getStatus());
        assertEquals(7, fixture.service().find(fixture.executionId()).getExitCode());
        assertEquals(null, fixture.service().find(fixture.executionId()).getFailureCode());
    }

    @Test
    void invalidArgumentsAreReturnedWithUsefulReason() throws Exception {
        ToolExecutionFixture fixture = fixture();
        when(fixture.tools().execute(anyString(), any(), any())).thenThrow(new IllegalArgumentException("command 必须是非空参数数组"));
        fixture.service().submit(fixture.sandboxId(), fixture.request());
        awaitTerminal(fixture.rows(), fixture.executionId());
        assertEquals("TOOL_ARGUMENT_INVALID", fixture.service().find(fixture.executionId()).getFailureCode());
        assertEquals("command 必须是非空参数数组", fixture.service().find(fixture.executionId()).getFailureReason());
    }

    @Test
    void unclassifiedFailurePersistsGenericCodeAndReason() throws Exception {
        ToolExecutionFixture fixture = fixture();
        when(fixture.tools().execute(anyString(), any(), any())).thenThrow(new IllegalStateException("internal detail"));

        fixture.service().submit(fixture.sandboxId(), fixture.request());
        awaitTerminal(fixture.rows(), fixture.executionId());

        assertEquals("TOOL_EXECUTION_FAILED", fixture.service().find(fixture.executionId()).getFailureCode());
        assertEquals("工具执行失败", fixture.service().find(fixture.executionId()).getFailureReason());
    }

    @Test
    void interruptedExecutionPersistsTimeoutCode() throws Exception {
        ToolExecutionFixture fixture = fixture();
        when(fixture.tools().execute(anyString(), any(), any())).thenThrow(new InterruptedException("timeout"));

        fixture.service().submit(fixture.sandboxId(), fixture.request());
        awaitTerminal(fixture.rows(), fixture.executionId());

        assertEquals("TIMED_OUT", fixture.service().find(fixture.executionId()).getStatus());
        assertEquals("TOOL_EXECUTION_TIMED_OUT", fixture.service().find(fixture.executionId()).getFailureCode());
        assertEquals("工具执行超时或被中断", fixture.service().find(fixture.executionId()).getFailureReason());
    }

    @Test
    void queuedExecutionCancellationIsIdempotent() {
        Map<String, ToolExecutionEntity> rows = new ConcurrentHashMap<>();
        ArrayList<ToolExecutionLogEntity> logs = new ArrayList<>();
        ToolExecutionMapper executions = executionMapper(rows);
        ToolExecutionLogMapper logMapper = logMapper(logs);
        SandboxService sandboxes = mock(SandboxService.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        ExecutorService waitingPool = mock(ExecutorService.class);
        SandboxProperties properties = new SandboxProperties();
        UUID sandboxId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        when(sandboxes.findAllocation(sandboxId)).thenReturn(allocation(sandboxId));
        ToolExecutionService service = new ToolExecutionService(sandboxes, paths, tools, executions, logMapper,
                new ObjectMapper().findAndRegisterModules(), properties, waitingPool, Clock.systemUTC());
        ToolExecutionRequest request = new ToolExecutionRequest();
        request.setExecutionId(executionId);
        request.setTool("file.read");

        service.submit(sandboxId, request);

        assertEquals("CANCELLED", service.cancel(executionId).getStatus());
        assertEquals("EXECUTION_CANCELLED", service.find(executionId).getFailureCode());
        assertEquals("执行已取消", service.find(executionId).getFailureReason());
        assertEquals("CANCELLED", service.cancel(executionId).getStatus());
    }

    @Test
    void startupRecoveryPersistsInterruptedCodeAndReason() {
        ToolExecutionFixture fixture = fixture();
        ToolExecutionEntity queued = queuedEntity(fixture.executionId(), fixture.sandboxId());
        fixture.rows().put(queued.getId(), queued);

        fixture.service().markInterruptedExecutions();

        assertEquals("INTERRUPTED", fixture.service().find(fixture.executionId()).getStatus());
        assertEquals("APPLICATION_RESTART_INTERRUPTED", fixture.service().find(fixture.executionId()).getFailureCode());
        assertEquals("Sandbox 重启，执行状态已中断", fixture.service().find(fixture.executionId()).getFailureReason());
    }

    @Test
    void queueRejectionPersistsFailureCodeBeforeReturningServiceError() {
        Map<String, ToolExecutionEntity> rows = new ConcurrentHashMap<>();
        ToolExecutionMapper executions = executionMapper(rows);
        ToolExecutionLogMapper logMapper = logMapper(new ArrayList<>());
        SandboxService sandboxes = mock(SandboxService.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        ExecutorService rejectedPool = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("unavailable")).when(rejectedPool).execute(any());
        UUID sandboxId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        when(sandboxes.findAllocation(sandboxId)).thenReturn(allocation(sandboxId));
        ToolExecutionService service = new ToolExecutionService(sandboxes, paths, tools, executions, logMapper,
                new ObjectMapper().findAndRegisterModules(), new SandboxProperties(), rejectedPool, Clock.systemUTC());
        ToolExecutionRequest request = request(executionId);

        SandboxException exception = assertThrows(SandboxException.class, () -> service.submit(sandboxId, request));

        assertEquals("EXECUTION_QUEUE_UNAVAILABLE", exception.getCode());
        assertEquals("FAILED", rows.get(executionId.toString()).getStatus());
        assertEquals("EXECUTION_QUEUE_UNAVAILABLE", rows.get(executionId.toString()).getFailureCode());
        assertEquals("命令执行队列不可用", rows.get(executionId.toString()).getFailureReason());
    }

    private ToolExecutionMapper executionMapper(Map<String, ToolExecutionEntity> rows) {
        ToolExecutionMapper mapper = mock(ToolExecutionMapper.class);
        when(mapper.selectById(anyString())).thenAnswer(invocation -> rows.get(invocation.getArgument(0)));
        when(mapper.markInterrupted(anyString(), any())).thenAnswer(invocation -> {
            int updated = 0;
            for (ToolExecutionEntity entity : rows.values()) {
                if (java.util.List.of("QUEUED", "RUNNING").contains(entity.getStatus())) {
                    entity.setStatus("INTERRUPTED");
                    entity.setFailureCode("APPLICATION_RESTART_INTERRUPTED");
                    entity.setFailureReason("Sandbox 重启，执行状态已中断");
                    entity.setFinishedAt(invocation.getArgument(1));
                    updated++;
                }
            }
            return updated;
        });
        when(mapper.insert(any(ToolExecutionEntity.class))).thenAnswer(invocation -> {
            ToolExecutionEntity entity = invocation.getArgument(0);
            rows.put(entity.getId(), entity);
            return 1;
        });
        when(mapper.markRunning(anyString(), anyString(), any())).thenAnswer(invocation -> {
            ToolExecutionEntity entity = rows.get(invocation.getArgument(0));
            if (entity == null || !"QUEUED".equals(entity.getStatus())) return 0;
            entity.setStatus("RUNNING");
            entity.setStartedAt(invocation.getArgument(2));
            return 1;
        });
        when(mapper.finishIfRunning(anyString(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
            ToolExecutionEntity entity = rows.get(invocation.getArgument(0));
            if (entity == null || !"RUNNING".equals(entity.getStatus())) return 0;
            entity.setStatus(invocation.getArgument(2));
            entity.setExitCode(invocation.getArgument(3));
            entity.setResultJson(invocation.getArgument(4));
            entity.setFailureCode(invocation.getArgument(5));
            entity.setFailureReason(invocation.getArgument(6));
            entity.setFinishedAt(invocation.getArgument(7));
            return 1;
        });
        when(mapper.markCancelled(anyString(), anyString(), any())).thenAnswer(invocation -> {
            ToolExecutionEntity entity = rows.get(invocation.getArgument(0));
            if (entity == null || !java.util.List.of("QUEUED", "RUNNING").contains(entity.getStatus())) return 0;
            entity.setStatus("CANCELLED");
            entity.setFailureCode("EXECUTION_CANCELLED");
            entity.setFailureReason("执行已取消");
            entity.setFinishedAt(invocation.getArgument(2));
            return 1;
        });
        when(mapper.rejectQueued(anyString(), anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            ToolExecutionEntity entity = rows.get(invocation.getArgument(0));
            if (entity == null || !"QUEUED".equals(entity.getStatus())) return 0;
            entity.setStatus("FAILED");
            entity.setFailureCode(invocation.getArgument(2));
            entity.setFailureReason(invocation.getArgument(3));
            entity.setFinishedAt(invocation.getArgument(4));
            return 1;
        });
        return mapper;
    }

    private ToolExecutionLogMapper logMapper(ArrayList<ToolExecutionLogEntity> logs) {
        ToolExecutionLogMapper mapper = mock(ToolExecutionLogMapper.class);
        when(mapper.selectMaxSequence(anyString())).thenAnswer(invocation -> (long) logs.size());
        when(mapper.insert(any())).thenAnswer(invocation -> {
            logs.add(invocation.getArgument(0));
            return 1;
        });
        return mapper;
    }

    private SandboxAllocation allocation(UUID sandboxId) {
        Instant now = Instant.now();
        return new SandboxAllocation(sandboxId, UUID.randomUUID(), "workspaces/" + UUID.randomUUID(), "dev-tools",
                "READY", "FAKE", now, now, now.plusSeconds(60), now.plusSeconds(3600),
                Duration.ofMinutes(15), null);
    }

    private ToolExecutionFixture fixture() {
        Map<String, ToolExecutionEntity> rows = new ConcurrentHashMap<>();
        ArrayList<ToolExecutionLogEntity> logs = new ArrayList<>();
        ToolExecutionMapper executions = executionMapper(rows);
        ToolExecutionLogMapper logMapper = logMapper(logs);
        SandboxService sandboxes = mock(SandboxService.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        SandboxProperties properties = new SandboxProperties();
        UUID sandboxId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        when(sandboxes.findAllocation(sandboxId)).thenReturn(allocation(sandboxId));
        ToolExecutionService service = new ToolExecutionService(sandboxes, paths, tools, executions, logMapper,
                new ObjectMapper().findAndRegisterModules(), properties, pool, Clock.systemUTC());
        return new ToolExecutionFixture(service, tools, rows, sandboxId, executionId, request(executionId), executions);
    }

    private ToolExecutionRequest request(UUID executionId) {
        ToolExecutionRequest request = new ToolExecutionRequest();
        request.setExecutionId(executionId);
        request.setTool("file.read");
        return request;
    }

    private ToolExecutionEntity queuedEntity(UUID executionId, UUID sandboxId) {
        ToolExecutionEntity entity = new ToolExecutionEntity();
        entity.setId(executionId.toString());
        entity.setOwnerInstanceId("local");
        entity.setSandboxId(sandboxId.toString());
        entity.setToolName("file.read");
        entity.setStatus("QUEUED");
        entity.setCreatedAt(java.time.LocalDateTime.now());
        return entity;
    }

    private void awaitTerminal(Map<String, ToolExecutionEntity> rows, UUID executionId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            ToolExecutionEntity entity = rows.get(executionId.toString());
            if (entity != null && java.util.List.of("SUCCEEDED", "FAILED", "TIMED_OUT").contains(entity.getStatus())) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("工具执行未在预期时间内结束");
    }

    private record ToolExecutionFixture(ToolExecutionService service, ToolRegistry tools,
                                        Map<String, ToolExecutionEntity> rows, UUID sandboxId, UUID executionId,
                                        ToolExecutionRequest request, ToolExecutionMapper mapper) {
    }
}
