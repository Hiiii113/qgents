package qg.qgent.sandbox.execution.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.dto.SandboxExecutionContext;
import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.execution.entity.ExecutionStatus;
import qg.qgent.sandbox.execution.entity.ToolExecutionEntity;
import qg.qgent.sandbox.execution.entity.ToolExecutionLogEntity;
import qg.qgent.sandbox.execution.handler.CommandRunTool;
import qg.qgent.sandbox.execution.mapper.ToolExecutionLogMapper;
import qg.qgent.sandbox.execution.mapper.ToolExecutionMapper;
import qg.qgent.sandbox.execution.output.CommandTail;
import qg.qgent.sandbox.filesystem.service.WorkspacePathResolver;
import qg.qgent.sandbox.runtime.SandboxAllocation;
import qg.qgent.sandbox.service.SandboxService;
import qg.qgent.support.TestIds;

class CommandExecutionServiceTest {
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
        MDC.clear();
    }

    @Test
    void startPersistsCallerRunAndWorkspaceAndWaitsForCompletion() throws Exception {
        Fixture fixture = fixture();
        Long child = TestIds.next(), workspace = TestIds.next();
        CountDownLatch release = new CountDownLatch(1);
        when(fixture.commands()
                .run(any(), any(), anyString(), anyList(), any(), anyMap(), any()))
            .thenAnswer(invocation -> {
                release.await();
                return outcome(0);
            });
        CommandExecution execution =
            fixture.service()
                .start(
                    fixture.sandboxId(),
                    child,
                    workspace,
                    ".",
                    List.of("npm", "test"),
                    Duration.ofMinutes(10),
                    Map.of());
        release.countDown();
        CommandExecution completed =
            fixture.service().await(execution.id(), Duration.ofSeconds(5));
        assertEquals(ExecutionStatus.SUCCEEDED, completed.status());
        assertEquals(child, fixture.rows().get(execution.id()).getAgentRunId());
        assertEquals(workspace, fixture.rows().get(execution.id()).getWorkspaceId());
    }

    @Test
    void commandWaitUsesCompletionNotificationAndPreservesContext() throws Exception {
        Fixture fixture = fixture();
        Long child = TestIds.next();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Long> observedRun = new AtomicReference<>();
        AtomicReference<String> observedRequest = new AtomicReference<>();
        when(fixture.commands()
                .run(any(), any(), anyString(), anyList(), any(), anyMap(), any()))
            .thenAnswer(invocation -> {
                observedRun.set(SandboxExecutionContext.currentAgentRunId());
                observedRequest.set(MDC.get("requestId"));
                started.countDown();
                release.await();
                return outcome(7);
            });
        MDC.put("requestId", "request-for-command");
        Long executionId =
            fixture.service()
                .start(
                    fixture.sandboxId(),
                    child,
                    null,
                    ".",
                    List.of("npm", "test"),
                    Duration.ofMinutes(10),
                    Map.of())
                .id();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        clearInvocations(fixture.mapper());
        try (var waiter = Executors.newSingleThreadExecutor()) {
            var result =
                waiter.submit(
                    () -> fixture.service().await(executionId, Duration.ofSeconds(5)));
            release.countDown();
            assertEquals(7, result.get(5, TimeUnit.SECONDS).exitCode());
        } finally {
            release.countDown();
        }
        assertEquals(child, observedRun.get());
        assertEquals("request-for-command", observedRequest.get());
        verify(fixture.mapper(), atMost(4)).selectById(executionId);
    }

    @Test
    void cancelledQueuedCommandNeverInvokesToolWhenWorkerBecomesAvailable() throws Exception {
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1);
        pool.execute(() -> {
            busy.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(busy.await(5, TimeUnit.SECONDS));
        Fixture fixture = fixture();
        Long executionId = start(fixture, fixture.sandboxId(), TestIds.next(), Map.of()).id();
        try {
            assertEquals(ExecutionStatus.CANCELLED, fixture.service().cancel(executionId).status());
            assertEquals(
                ExecutionStatus.CANCELLED,
                fixture.service().await(executionId, Duration.ofSeconds(1)).status());
        } finally {
            release.countDown();
        }
        // 同一个单线程池的后续任务完成，表示先前排队的命令已经走完收尾。
        pool.submit(() -> {}).get(5, TimeUnit.SECONDS);
        verify(fixture.commands(), never())
            .run(any(), any(), anyString(), anyList(), any(), anyMap(), any());
        assertEquals(ExecutionStatus.CANCELLED, fixture.service().find(executionId).status());
    }

    @Test
    void shortWaitReturnsHandleAndCancellationWakesWaiter() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        when(fixture.commands()
                .run(any(), any(), anyString(), anyList(), any(), anyMap(), any()))
            .thenAnswer(invocation -> {
                started.countDown();
                release.await();
                return outcome(0);
            });
        Long executionId = start(fixture, fixture.sandboxId(), TestIds.next(), Map.of()).id();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(
            ExecutionStatus.RUNNING,
            fixture.service().await(executionId, Duration.ofMillis(20)).status());
        try (var waiter = Executors.newSingleThreadExecutor()) {
            var result =
                waiter.submit(
                    () -> fixture.service().await(executionId, Duration.ofSeconds(5)));
            assertEquals(
                ExecutionStatus.CANCELLED, fixture.service().cancel(executionId).status());
            assertEquals(ExecutionStatus.CANCELLED, result.get(5, TimeUnit.SECONDS).status());
            assertEquals(
                ExecutionStatus.CANCELLED, fixture.service().cancel(executionId).status());
        } finally {
            release.countDown();
        }
    }

    @Test
    void stoppingSandboxWaitsUntilInterruptedCommandWorkerActuallyExits() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch started = new CountDownLatch(1),
            interrupted = new CountDownLatch(1),
            release = new CountDownLatch(1);
        when(fixture.commands()
                .run(any(), any(), anyString(), anyList(), any(), anyMap(), any()))
            .thenAnswer(invocation -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    release.await();
                }
                return outcome(0);
            });
        Long executionId = start(fixture, fixture.sandboxId(), TestIds.next(), Map.of()).id();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        when(fixture.mapper().selectActiveIdsBySandbox(fixture.sandboxId().toString(), "local"))
            .thenReturn(List.of(executionId));

        try (ExecutorService waiter = Executors.newSingleThreadExecutor()) {
            CompletableFuture<Void> stopping =
                CompletableFuture.runAsync(
                    () -> fixture.service().stopAndAwaitBySandbox(fixture.sandboxId()), waiter);
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(stopping.isDone());
            release.countDown();
            stopping.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void stoppingOneAgentRunCancelsAndWaitsOnlyItsCommandWorker() throws Exception {
        Fixture fixture = fixture();
        Long childRunId = TestIds.next();
        CountDownLatch started = new CountDownLatch(1),
            interrupted = new CountDownLatch(1),
            release = new CountDownLatch(1);
        when(fixture.commands()
                .run(any(), any(), anyString(), anyList(), any(), anyMap(), any()))
            .thenAnswer(invocation -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    release.await();
                }
                return outcome(0);
            });
        Long executionId = start(fixture, fixture.sandboxId(), childRunId, Map.of()).id();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        when(fixture.mapper().selectActiveIdsByAgentRun(childRunId, "local"))
            .thenReturn(List.of(executionId));

        try (ExecutorService waiter = Executors.newSingleThreadExecutor()) {
            CompletableFuture<Void> stopping =
                CompletableFuture.runAsync(
                    () -> fixture.service().stopAndAwaitByAgentRun(childRunId), waiter);
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(stopping.isDone());
            release.countDown();
            stopping.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        verify(fixture.mapper()).selectActiveIdsByAgentRun(childRunId, "local");
    }

    @Test
    void workspaceReleaseStillWaitsForAgentRunCommandAlreadyMarkedCancelled() throws Exception {
        Fixture fixture = fixture();
        Long agentRunId = TestIds.next();
        CountDownLatch started = new CountDownLatch(1),
            interrupted = new CountDownLatch(1),
            release = new CountDownLatch(1);
        when(fixture.commands()
                .run(any(), any(), anyString(), anyList(), any(), anyMap(), any()))
            .thenAnswer(invocation -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException secondInterrupt) {
                        Thread.currentThread().interrupt();
                    }
                }
                return outcome(0);
            });
        start(fixture, fixture.sandboxId(), agentRunId, Map.of());
        assertTrue(started.await(5, TimeUnit.SECONDS));
        fixture.service().cancelByAgentRun(agentRunId);
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));

        try (var waiter = Executors.newSingleThreadExecutor()) {
            CompletableFuture<Void> releaseWork =
                CompletableFuture.runAsync(
                    () -> fixture.service().stopAndAwaitBySandbox(fixture.sandboxId()), waiter);
            Thread.sleep(100);
            assertFalse(
                releaseWork.isDone(), "release must include workers cancelled earlier by run ID");
            release.countDown();
            releaseWork.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void startPersistsQueuedRecordAndCompletesInBackground() throws Exception {
        Map<Long, ToolExecutionEntity> rows = new ConcurrentHashMap<>();
        ArrayList<ToolExecutionLogEntity> logs = new ArrayList<>();
        ToolExecutionMapper executions = executionMapper(rows);
        ToolExecutionLogMapper logMapper = logMapper(logs);
        SandboxService sandboxes = mock(SandboxService.class);
        CommandRunTool commands = mock(CommandRunTool.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        SandboxProperties properties = new SandboxProperties();
        UUID sandboxId = UUID.randomUUID();
        SandboxAllocation sandbox = allocation(sandboxId);
        when(sandboxes.findAllocation(sandboxId)).thenReturn(sandbox);
        when(paths.resolveLocal(sandbox.getWorkspaceStorageKey())).thenReturn(java.nio.file.Path.of("."));
        when(commands.run(any(), any(), anyString(), anyList(), any(), anyMap(), any()))
            .thenReturn(outcome(0));
        CommandExecutionService service =
            new CommandExecutionService(
                sandboxes,
                paths,
                commands,
                executions,
                logMapper,
                new ObjectMapper().findAndRegisterModules(),
                properties,
                pool,
                Clock.systemUTC());
        Map<String, String> environment = Map.of("GIT_CONFIG_VALUE_0", "temporary-secret");

        Long executionId =
            service.start(
                    sandboxId,
                    TestIds.next(),
                    TestIds.next(),
                    ".",
                    List.of("git", "push"),
                    Duration.ofMinutes(10),
                    environment)
                .id();
        for (int attempt = 0;
            attempt < 100 && rows.get(executionId).getStatus() != ExecutionStatus.SUCCEEDED;
            attempt++) {
            Thread.sleep(10);
        }

        assertEquals(ExecutionStatus.SUCCEEDED, service.find(executionId).status());
        assertFalse(rows.get(executionId).getArgumentsJson().contains("temporary-secret"));
        assertTrue(rows.get(executionId).getArgumentsJson().contains("git"));
        verify(commands)
            .run(
                any(),
                any(),
                anyString(),
                anyList(),
                any(),
                org.mockito.ArgumentMatchers.argThat(
                    context -> "temporary-secret".equals(context.get("GIT_CONFIG_VALUE_0"))),
                any());
        assertEquals(null, service.find(executionId).failureCode());
        assertEquals(null, service.find(executionId).failureReason());
        assertFalse(logs.isEmpty());
    }

    @Test
    void sandboxExceptionPersistsStructuredFailureCodeAndReason() throws Exception {
        Fixture fixture = fixture();
        when(fixture.commands()
                .run(any(), any(), anyString(), anyList(), any(), anyMap(), any()))
            .thenThrow(
                new SandboxException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "COMMAND_NOT_ALLOWED",
                    "命令不在允许列表中"));

        Long executionId = start(fixture, fixture.sandboxId(), TestIds.next(), Map.of()).id();
        awaitTerminal(fixture.rows(), executionId);

        assertEquals(ExecutionStatus.FAILED, fixture.service().find(executionId).status());
        assertEquals("COMMAND_NOT_ALLOWED", fixture.service().find(executionId).failureCode());
        assertEquals("命令不在允许列表中", fixture.service().find(executionId).failureReason());
    }

    @Test
    void sandboxExceptionFailureReasonIsRedactedAndBounded() throws Exception {
        Fixture fixture = fixture();
        String detail =
            "Bearer super-secret token=abc https://example.invalid/test?api_key=abc "
                + "C:\\Users\\Administrator\\private.txt "
                + "x".repeat(1200);
        when(fixture.commands()
                .run(any(), any(), anyString(), anyList(), any(), anyMap(), any()))
            .thenThrow(
                new SandboxException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "TOOL_PATH_INVALID",
                    detail));

        Long executionId = start(fixture, fixture.sandboxId(), TestIds.next(), Map.of()).id();
        awaitTerminal(fixture.rows(), executionId);

        String reason = fixture.service().find(executionId).failureReason();
        assertEquals("TOOL_PATH_INVALID", fixture.service().find(executionId).failureCode());
        assertFalse(reason.contains("super-secret"));
        assertFalse(reason.contains("token=abc"));
        assertFalse(reason.contains("C:\\Users\\Administrator"));
        assertTrue(reason.contains("https://example.invalid/test?api_key=[redacted]"));
        assertTrue(reason.contains("[redacted]"));
        assertTrue(reason.contains("[host path omitted]"));
        assertTrue(reason.length() <= 1024);
    }

    @Test
    void nonzeroCommandExitIsACompletedResult() throws Exception {
        Fixture fixture = fixture();
        when(fixture.commands()
                .run(any(), any(), anyString(), anyList(), any(), anyMap(), any()))
            .thenReturn(outcome(7));
        Long executionId = start(fixture, fixture.sandboxId(), TestIds.next(), Map.of()).id();
        awaitTerminal(fixture.rows(), executionId);
        assertEquals(ExecutionStatus.SUCCEEDED, fixture.service().find(executionId).status());
        assertEquals(7, fixture.service().find(executionId).exitCode());
        assertEquals(null, fixture.service().find(executionId).failureCode());
    }

    @Test
    void invalidArgumentsAreReturnedWithUsefulReason() throws Exception {
        Fixture fixture = fixture();
        when(fixture.commands()
                .run(any(), any(), anyString(), anyList(), any(), anyMap(), any()))
            .thenThrow(new IllegalArgumentException("工作目录必须在工作区内"));
        Long executionId = start(fixture, fixture.sandboxId(), TestIds.next(), Map.of()).id();
        awaitTerminal(fixture.rows(), executionId);
        assertEquals("TOOL_ARGUMENT_INVALID", fixture.service().find(executionId).failureCode());
        assertEquals("工作目录必须在工作区内", fixture.service().find(executionId).failureReason());
    }

    @Test
    void unclassifiedFailurePersistsGenericCodeAndReason() throws Exception {
        Fixture fixture = fixture();
        when(fixture.commands()
                .run(any(), any(), anyString(), anyList(), any(), anyMap(), any()))
            .thenThrow(new IllegalStateException("internal detail"));

        Long executionId = start(fixture, fixture.sandboxId(), TestIds.next(), Map.of()).id();
        awaitTerminal(fixture.rows(), executionId);

        assertEquals("TOOL_EXECUTION_FAILED", fixture.service().find(executionId).failureCode());
        assertEquals("工具执行失败", fixture.service().find(executionId).failureReason());
    }

    @Test
    void interruptedExecutionPersistsTimeoutCode() throws Exception {
        Fixture fixture = fixture();
        when(fixture.commands()
                .run(any(), any(), anyString(), anyList(), any(), anyMap(), any()))
            .thenThrow(new InterruptedException("timeout"));

        Long executionId = start(fixture, fixture.sandboxId(), TestIds.next(), Map.of()).id();
        awaitTerminal(fixture.rows(), executionId);

        assertEquals(ExecutionStatus.TIMED_OUT, fixture.service().find(executionId).status());
        assertEquals("TOOL_EXECUTION_TIMED_OUT", fixture.service().find(executionId).failureCode());
        assertEquals("工具执行超时或被中断", fixture.service().find(executionId).failureReason());
    }

    @Test
    void queuedExecutionCancellationIsIdempotent() {
        Map<Long, ToolExecutionEntity> rows = new ConcurrentHashMap<>();
        ArrayList<ToolExecutionLogEntity> logs = new ArrayList<>();
        ToolExecutionMapper executions = executionMapper(rows);
        ToolExecutionLogMapper logMapper = logMapper(logs);
        SandboxService sandboxes = mock(SandboxService.class);
        CommandRunTool commands = mock(CommandRunTool.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        ExecutorService waitingPool = mock(ExecutorService.class);
        SandboxProperties properties = new SandboxProperties();
        UUID sandboxId = UUID.randomUUID();
        when(sandboxes.findAllocation(sandboxId)).thenReturn(allocation(sandboxId));
        CommandExecutionService service =
            new CommandExecutionService(
                sandboxes,
                paths,
                commands,
                executions,
                logMapper,
                new ObjectMapper().findAndRegisterModules(),
                properties,
                waitingPool,
                Clock.systemUTC());

        Long executionId =
            service.start(
                    sandboxId,
                    TestIds.next(),
                    null,
                    ".",
                    List.of("npm", "test"),
                    Duration.ofMinutes(1),
                    Map.of())
                .id();

        assertEquals(ExecutionStatus.CANCELLED, service.cancel(executionId).status());
        assertEquals("EXECUTION_CANCELLED", service.find(executionId).failureCode());
        assertEquals("执行已取消", service.find(executionId).failureReason());
        assertEquals(ExecutionStatus.CANCELLED, service.cancel(executionId).status());
    }

    @Test
    void startupRecoveryPersistsInterruptedCodeAndReason() {
        Fixture fixture = fixture();
        Long executionId = TestIds.next();
        ToolExecutionEntity queued = queuedEntity(executionId, fixture.sandboxId());
        fixture.rows().put(queued.getId(), queued);

        fixture.service().markInterruptedExecutions();

        assertEquals(ExecutionStatus.INTERRUPTED, fixture.service().find(executionId).status());
        assertEquals(
            "APPLICATION_RESTART_INTERRUPTED", fixture.service().find(executionId).failureCode());
        assertEquals(
            "Sandbox 重启，执行状态已中断", fixture.service().find(executionId).failureReason());
    }

    @Test
    void queueRejectionPersistsFailureCodeBeforeReturningServiceError() {
        Map<Long, ToolExecutionEntity> rows = new ConcurrentHashMap<>();
        ToolExecutionMapper executions = executionMapper(rows);
        ToolExecutionLogMapper logMapper = logMapper(new ArrayList<>());
        SandboxService sandboxes = mock(SandboxService.class);
        CommandRunTool commands = mock(CommandRunTool.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        ExecutorService rejectedPool = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("unavailable")).when(rejectedPool).execute(any());
        UUID sandboxId = UUID.randomUUID();
        when(sandboxes.findAllocation(sandboxId)).thenReturn(allocation(sandboxId));
        CommandExecutionService service =
            new CommandExecutionService(
                sandboxes,
                paths,
                commands,
                executions,
                logMapper,
                new ObjectMapper().findAndRegisterModules(),
                new SandboxProperties(),
                rejectedPool,
                Clock.systemUTC());

        SandboxException exception =
            assertThrows(
                SandboxException.class,
                () ->
                    service.start(
                        sandboxId,
                        TestIds.next(),
                        null,
                        ".",
                        List.of("npm", "test"),
                        Duration.ofMinutes(1),
                        Map.of()));

        assertEquals("EXECUTION_QUEUE_UNAVAILABLE", exception.getCode());
        ToolExecutionEntity rejected = rows.values().iterator().next();
        assertEquals(ExecutionStatus.FAILED, rejected.getStatus());
        assertEquals("EXECUTION_QUEUE_UNAVAILABLE", rejected.getFailureCode());
        assertEquals("命令执行队列不可用", rejected.getFailureReason());
    }

    @Test
    void outputRejectsRecordsThatAreNotCommands() {
        Fixture fixture = fixture();
        Long executionId = TestIds.next();
        ToolExecutionEntity legacy = queuedEntity(executionId, fixture.sandboxId());
        legacy.setToolName("file.read");
        fixture.rows().put(executionId, legacy);

        SandboxException exception =
            assertThrows(
                SandboxException.class,
                () -> fixture.service().output(executionId, 0, 0, 16000));

        assertEquals("NOT_COMMAND_EXECUTION", exception.getCode());
    }

    private CommandExecution start(
        Fixture fixture, UUID sandboxId, Long agentRunId, Map<String, String> environment) {
        return fixture
            .service()
            .start(
                sandboxId,
                agentRunId,
                null,
                ".",
                List.of("npm", "test"),
                Duration.ofMinutes(10),
                environment);
    }

    private CommandRunTool.Outcome outcome(int exitCode) {
        return new CommandRunTool.Outcome(
            exitCode, new CommandTail("done", false), new CommandTail("", false));
    }

    private ToolExecutionMapper executionMapper(Map<Long, ToolExecutionEntity> rows) {
        ToolExecutionMapper mapper = mock(ToolExecutionMapper.class);
        when(mapper.selectById(any())).thenAnswer(invocation -> rows.get(invocation.getArgument(0)));
        when(mapper.selectActiveIdsByAgentRun(any(), anyString()))
            .thenAnswer(invocation ->
                rows.values().stream()
                    .filter(entity -> invocation.getArgument(0).equals(entity.getAgentRunId()))
                    .filter(entity ->
                        invocation.getArgument(1).equals(entity.getOwnerInstanceId()))
                    .filter(entity -> !entity.getStatus().terminal())
                    .map(ToolExecutionEntity::getId)
                    .toList());
        when(mapper.markInterrupted(anyString(), any()))
            .thenAnswer(invocation -> {
                int updated = 0;
                for (ToolExecutionEntity entity : rows.values()) {
                    if (!entity.getStatus().terminal()) {
                        entity.setStatus(ExecutionStatus.INTERRUPTED);
                        entity.setFailureCode("APPLICATION_RESTART_INTERRUPTED");
                        entity.setFailureReason("Sandbox 重启，执行状态已中断");
                        entity.setFinishedAt(invocation.getArgument(1));
                        updated++;
                    }
                }
                return updated;
            });
        when(mapper.insert(any(ToolExecutionEntity.class)))
            .thenAnswer(invocation -> {
                ToolExecutionEntity entity = invocation.getArgument(0);
                if (entity.getId() == null) entity.setId(TestIds.next());
                rows.put(entity.getId(), entity);
                return 1;
            });
        when(mapper.markRunning(any(), anyString(), any()))
            .thenAnswer(invocation -> {
                ToolExecutionEntity entity = rows.get(invocation.getArgument(0));
                if (entity == null || entity.getStatus() != ExecutionStatus.QUEUED) return 0;
                entity.setStatus(ExecutionStatus.RUNNING);
                entity.setStartedAt(invocation.getArgument(2));
                return 1;
            });
        when(mapper.finishIfRunning(
                any(), anyString(), any(ExecutionStatus.class), any(), any(), any(), any(), any()))
            .thenAnswer(invocation -> {
                ToolExecutionEntity entity = rows.get(invocation.getArgument(0));
                if (entity == null || entity.getStatus() != ExecutionStatus.RUNNING) return 0;
                entity.setStatus(invocation.getArgument(2, ExecutionStatus.class));
                entity.setExitCode(invocation.getArgument(3));
                entity.setResultJson(invocation.getArgument(4));
                entity.setFailureCode(invocation.getArgument(5));
                entity.setFailureReason(invocation.getArgument(6));
                entity.setFinishedAt(invocation.getArgument(7));
                return 1;
            });
        when(mapper.markCancelled(any(), anyString(), any()))
            .thenAnswer(invocation -> {
                ToolExecutionEntity entity = rows.get(invocation.getArgument(0));
                if (entity == null || entity.getStatus().terminal()) return 0;
                entity.setStatus(ExecutionStatus.CANCELLED);
                entity.setFailureCode("EXECUTION_CANCELLED");
                entity.setFailureReason("执行已取消");
                entity.setFinishedAt(invocation.getArgument(2));
                return 1;
            });
        when(mapper.rejectQueued(any(), anyString(), anyString(), anyString(), any()))
            .thenAnswer(invocation -> {
                ToolExecutionEntity entity = rows.get(invocation.getArgument(0));
                if (entity == null || entity.getStatus() != ExecutionStatus.QUEUED) return 0;
                entity.setStatus(ExecutionStatus.FAILED);
                entity.setFailureCode(invocation.getArgument(2));
                entity.setFailureReason(invocation.getArgument(3));
                entity.setFinishedAt(invocation.getArgument(4));
                return 1;
            });
        return mapper;
    }

    private ToolExecutionLogMapper logMapper(ArrayList<ToolExecutionLogEntity> logs) {
        ToolExecutionLogMapper mapper = mock(ToolExecutionLogMapper.class);
        when(mapper.selectMaxSequence(any())).thenAnswer(invocation -> (long) logs.size());
        when(mapper.insert(any()))
            .thenAnswer(invocation -> {
                logs.add(invocation.getArgument(0));
                return 1;
            });
        return mapper;
    }

    private SandboxAllocation allocation(UUID sandboxId) {
        Instant now = Instant.now();
        return new SandboxAllocation(
            sandboxId,
            TestIds.next(),
            TestIds.next() + "/workspaces/default",
            "dev-tools",
            "READY",
            "FAKE",
            now,
            now,
            now.plusSeconds(60),
            now.plusSeconds(3600),
            Duration.ofMinutes(15),
            null);
    }

    private Fixture fixture() {
        Map<Long, ToolExecutionEntity> rows = new ConcurrentHashMap<>();
        ArrayList<ToolExecutionLogEntity> logs = new ArrayList<>();
        ToolExecutionMapper executions = executionMapper(rows);
        ToolExecutionLogMapper logMapper = logMapper(logs);
        SandboxService sandboxes = mock(SandboxService.class);
        CommandRunTool commands = mock(CommandRunTool.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        SandboxProperties properties = new SandboxProperties();
        UUID sandboxId = UUID.randomUUID();
        SandboxAllocation sandbox = allocation(sandboxId);
        when(sandboxes.findAllocation(sandboxId)).thenReturn(sandbox);
        when(paths.resolveLocal(sandbox.getWorkspaceStorageKey()))
            .thenReturn(java.nio.file.Path.of("."));
        CommandExecutionService service =
            new CommandExecutionService(
                sandboxes,
                paths,
                commands,
                executions,
                logMapper,
                new ObjectMapper().findAndRegisterModules(),
                properties,
                pool,
                Clock.systemUTC());
        return new Fixture(service, commands, rows, sandboxId, executions);
    }

    private ToolExecutionEntity queuedEntity(Long executionId, UUID sandboxId) {
        ToolExecutionEntity entity = new ToolExecutionEntity();
        entity.setId(executionId);
        entity.setOwnerInstanceId("local");
        entity.setSandboxId(sandboxId.toString());
        entity.setToolName("command.run");
        entity.setStatus(ExecutionStatus.QUEUED);
        entity.setCreatedAt(java.time.LocalDateTime.now());
        return entity;
    }

    private void awaitTerminal(Map<Long, ToolExecutionEntity> rows, Long executionId)
        throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            ToolExecutionEntity entity = rows.get(executionId);
            if (entity != null && entity.getStatus().terminal()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("命令执行未在预期时间内结束");
    }

    private record Fixture(
        CommandExecutionService service,
        CommandRunTool commands,
        Map<Long, ToolExecutionEntity> rows,
        UUID sandboxId,
        ToolExecutionMapper mapper) {}
}
