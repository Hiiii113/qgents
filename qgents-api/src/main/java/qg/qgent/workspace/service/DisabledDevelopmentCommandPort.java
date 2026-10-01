package qg.qgent.workspace.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import qg.qgent.workspace.dto.DevelopmentCommandResult;

import java.util.UUID;

/** 未启用 Worker 时提供明确失败的固定开发命令端口，避免 Coding Agent 回退到宿主机命令。 */
@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "false", matchIfMissing = true)
class DisabledDevelopmentCommandPort implements DevelopmentCommandPort {

    @Override
    public DevelopmentCommandResult run(UUID workspaceId, String repositoryPath, DevelopmentCommandId commandId) {
        return DevelopmentCommandResult.unavailable(commandId);
    }
}
