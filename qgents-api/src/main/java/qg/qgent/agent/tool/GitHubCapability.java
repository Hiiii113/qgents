package qg.qgent.agent.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.workspace.service.WorkspaceCommands;
import qg.qgent.github.service.GitHubRepositoryService;
import qg.qgent.github.client.GitHubAppClient;

import java.util.UUID;
import java.util.function.Supplier;

/** 按 Agent 开关提供 GitHub 工具，使用当前项目导入仓库的 App 安装凭证。 */
@Component
@RequiredArgsConstructor
public class GitHubCapability {
    private final WorkspaceCommands commands;
    private final GitHubRepositoryService repositories;

    public GitHubTools tools(AgentEntity agent, UUID actor, UUID projectId, Supplier<UUID> workspace) {
        if (!Boolean.TRUE.equals(agent.getGithubEnabled())) {
            return null;
        }
        return new GitHubTools(GitHubAppClient.httpBuilder().build(),
                repository -> repositories.repositoryToken(actor, projectId, agent.getTeamId(), repository),
                commands, workspace, () -> repositories.listProjectRepositories(actor, projectId));
    }
}
