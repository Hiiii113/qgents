package qg.qgent.sandbox.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import qg.qgent.sandbox.execution.service.CommandExecutionService;
import qg.qgent.sandbox.metrics.SandboxMetrics;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定期取消过期执行并回收沙箱，作为控制层主动销毁之外的安全兜底。
 */
@Slf4j
@Service
public class SandboxCleanupService {
    private final SandboxService sandboxes;
    private final CommandExecutionService executions;
    private final SandboxSessionManager sessions;
    private final Executor cleanupExecutor;
    private final SandboxMetrics metrics;
    // 清理会等待命令线程退出；上一轮未结束时只跳过本轮，不排队堆积。
    private final AtomicBoolean cleanupRunning = new AtomicBoolean();

    public SandboxCleanupService(
        SandboxService sandboxes,
        CommandExecutionService executions,
        SandboxSessionManager sessions,
        Executor cleanupExecutor) {
        this(sandboxes, executions, sessions, cleanupExecutor, SandboxMetrics.noop());
    }

    @Autowired
    public SandboxCleanupService(
        SandboxService sandboxes,
        CommandExecutionService executions,
        SandboxSessionManager sessions,
        @Qualifier("sandboxCleanupExecutor") Executor cleanupExecutor,
        SandboxMetrics metrics) {
        this.sandboxes = sandboxes;
        this.executions = executions;
        this.sessions = sessions;
        this.cleanupExecutor = cleanupExecutor;
        this.metrics = metrics;
    }

    /**
     * 调度线程只提交清理任务，不等待命令退出；上一轮未结束时跳过本轮。
     */
    @Scheduled(fixedDelayString = "${sandbox.cleanup-interval:30s}")
    public void cleanupExpiredSandboxes() {
        if (!cleanupRunning.compareAndSet(false, true)) {
            log.debug("previous sandbox cleanup still running, skip this round");
            return;
        }
        try {
            cleanupExecutor.execute(this::runCleanup);
        } catch (RejectedExecutionException exception) {
            cleanupRunning.set(false);
            log.warn("sandbox cleanup executor rejected the task: {}", exception.getMessage());
        }
    }

    /**
     * 回收达到空闲期限或最大寿命的沙箱。
     * 先取消并等待执行线程退出，再销毁底层容器，避免与命令中断后的容器恢复竞争。
     * 销毁后同步移除会话缓存，下一次工具调用会按需重建沙箱，避免残留会话一直占用 Workspace。
     * 不会删除持久 Workspace。
     */
    private void runCleanup() {
        try {
            sandboxes
                .expiredSandboxIds()
                .forEach(sandboxId -> {
                    executions.stopAndAwaitBySandbox(sandboxId);
                    try {
                        sandboxes.destroy(sandboxId);
                    } catch (RuntimeException exception) {
                        metrics.recordReclaimed("destroy_error");
                        throw exception;
                    }
                    sessions.forgetSandbox(sandboxId);
                    metrics.recordReclaimed("expired_cleanup");
                    log.info("sandbox reclaimed by scheduled cleanup sandboxId={}", sandboxId);
                });
        } catch (RuntimeException exception) {
            // 后台任务没有调用方，失败必须留下可排查的日志，并保证下一轮仍可提交。
            log.error("scheduled sandbox cleanup failed", exception);
        } finally {
            cleanupRunning.set(false);
        }
    }
}
