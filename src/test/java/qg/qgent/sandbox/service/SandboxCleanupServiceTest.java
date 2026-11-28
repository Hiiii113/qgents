package qg.qgent.sandbox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.execution.service.CommandExecutionService;
import qg.qgent.sandbox.runtime.FakeContainerRuntime;
import qg.qgent.support.TestIds;
import qg.qgent.workspace.service.WorkspaceOperationLock;

class SandboxCleanupServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void cleanupCancelsCommandsAndDestroysExpiredSandbox() {
        SandboxProperties properties = new SandboxProperties();
        properties.setWorkspaceMetadataRoot(temporaryDirectory.resolve("metadata").toString());
        FakeContainerRuntime runtime = new FakeContainerRuntime();
        Clock clock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC);
        SandboxService sandboxes =
            new SandboxService(runtime, properties, clock, new WorkspaceOperationLock(properties));
        CommandExecutionService executions = mock(CommandExecutionService.class);
        SandboxSessionManager sessions = mock(SandboxSessionManager.class);
        SandboxCleanupService cleanup =
            new SandboxCleanupService(sandboxes, executions, sessions, Runnable::run);
        UUID sandboxId = UUID.randomUUID();
        sandboxes.create(sandboxId, TestIds.next(), storageKey(), "dev-tools");
        runtime.find(sandboxId).orElseThrow().setExpiresAt(clock.instant().minusSeconds(1));

        cleanup.cleanupExpiredSandboxes();

        verify(executions).stopAndAwaitBySandbox(sandboxId);
        verify(sessions).forgetSandbox(sandboxId);
        assertTrue(sandboxes.find(sandboxId).isEmpty());
    }

    @Test
    void scheduledTriggerReturnsWhileCleanupWaitsForCommandWorkers() throws Exception {
        SandboxService sandboxes = mock(SandboxService.class);
        CommandExecutionService executions = mock(CommandExecutionService.class);
        SandboxSessionManager sessions = mock(SandboxSessionManager.class);
        UUID sandboxId = UUID.randomUUID();
        when(sandboxes.expiredSandboxIds()).thenReturn(List.of(sandboxId));
        CountDownLatch stopping = new CountDownLatch(1), exited = new CountDownLatch(1);
        doAnswer(
                invocation -> {
                    stopping.countDown();
                    exited.await(5, TimeUnit.SECONDS);
                    return null;
                })
            .when(executions)
            .stopAndAwaitBySandbox(sandboxId);

        try (var cleanupExecutor = Executors.newSingleThreadExecutor();
             var triggerExecutor = Executors.newSingleThreadExecutor()) {
            SandboxCleanupService cleanup =
                new SandboxCleanupService(sandboxes, executions, sessions, cleanupExecutor);
            var trigger = triggerExecutor.submit(cleanup::cleanupExpiredSandboxes);

            assertTrue(stopping.await(5, TimeUnit.SECONDS));
            // 清理仍卡在等待命令退出时，调度触发必须已经返回。
            trigger.get(5, TimeUnit.SECONDS);
            verify(sandboxes, never()).destroy(any());

            exited.countDown();
            verify(sessions, timeout(5000)).forgetSandbox(sandboxId);
            var order = inOrder(executions, sandboxes, sessions);
            order.verify(executions).stopAndAwaitBySandbox(sandboxId);
            order.verify(sandboxes).destroy(sandboxId);
            order.verify(sessions).forgetSandbox(sandboxId);
        } finally {
            exited.countDown();
        }
    }

    @Test
    void overlappingTriggerDoesNotQueueAnotherCleanupUntilTheFirstFinishes() {
        SandboxService sandboxes = mock(SandboxService.class);
        CommandExecutionService executions = mock(CommandExecutionService.class);
        SandboxSessionManager sessions = mock(SandboxSessionManager.class);
        UUID sandboxId = UUID.randomUUID();
        when(sandboxes.expiredSandboxIds()).thenReturn(List.of(sandboxId));
        ManualExecutor cleanupExecutor = new ManualExecutor();
        SandboxCleanupService cleanup =
            new SandboxCleanupService(sandboxes, executions, sessions, cleanupExecutor);

        cleanup.cleanupExpiredSandboxes();
        cleanup.cleanupExpiredSandboxes();

        assertEquals(1, cleanupExecutor.queued());

        cleanupExecutor.runNext();

        verify(sandboxes).destroy(sandboxId);
        verify(sessions).forgetSandbox(sandboxId);

        cleanup.cleanupExpiredSandboxes();

        // 上一轮结束后恢复提交，不会永久停在“运行中”。
        assertEquals(1, cleanupExecutor.queued());
    }

    private String storageKey() {
        return TestIds.next() + "/workspaces/default";
    }

    private static final class ManualExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        int queued() {
            return tasks.size();
        }

        void runNext() {
            tasks.removeFirst().run();
        }
    }
}
