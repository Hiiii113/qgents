package qg.qgent.workspace.service;

import org.junit.jupiter.api.Test;

import qg.qgent.workspace.dto.GitDiffResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Disabled 占位端口契约测试：任何 diff 请求都必须返回明确 unavailable，
 * 绝不落到宿主机执行、不启动任何进程，也不伪造 base/head commit。
 */
class DisabledPortContractTest {

    private final UUID workspaceId = UUID.randomUUID();

    @Test
    void disabledWorkspaceDiffAccessAlwaysReturnsUnavailable() {
        DisabledWorkspaceDiffAccess access = new DisabledWorkspaceDiffAccess();

        GitDiffResult result = access.diff(workspaceId);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("not available");
        assertThat(result.baseCommit()).isEmpty();
        assertThat(result.headCommit()).isEmpty();
    }
}
