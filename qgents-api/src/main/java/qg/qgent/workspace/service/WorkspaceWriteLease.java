package qg.qgent.workspace.service;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/** 一次写入租约的持有者信息和服务端令牌。 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class WorkspaceWriteLease {
    @Getter
    private final UUID projectId;
    @Getter
    private final UUID workspaceId;
    @Getter
    private final UUID agentRunId;
    private final String token;

    String token() {
        return token;
    }
}
