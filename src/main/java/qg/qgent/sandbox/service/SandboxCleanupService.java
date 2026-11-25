package qg.qgent.sandbox.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import qg.qgent.sandbox.execution.service.CommandExecutionService;

/**
 * 定期取消过期执行并回收沙箱，作为控制层主动销毁之外的安全兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxCleanupService {
    private final SandboxService sandboxes;
    private final CommandExecutionService executions;
    private final SandboxSessionManager sessions;

    /**
     * 回收达到空闲期限或最大寿命的沙箱
     * 先取消并等待执行线程退出，再销毁底层容器，避免与命令中断后的容器恢复竞争。
     * 销毁后同步移除会话缓存，下一次工具调用会按需重建沙箱，避免残留会话一直占用 Workspace。
     * 不会删除持久 Workspace。
     */
    @Scheduled(fixedDelayString = "${sandbox.cleanup-interval:30s}")
    public void cleanupExpiredSandboxes() {
        sandboxes
            .expiredSandboxIds()
            .forEach(sandboxId -> {
                executions.stopAndAwaitBySandbox(sandboxId);
                sandboxes.destroy(sandboxId);
                sessions.forgetSandbox(sandboxId);
                log.info("sandbox reclaimed by scheduled cleanup sandboxId={}", sandboxId);
            });
    }
}
