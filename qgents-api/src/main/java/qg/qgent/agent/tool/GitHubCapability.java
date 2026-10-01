package qg.qgent.agent.tool;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.workspace.service.WorkspaceCommands;
import qg.qgent.github.service.GitHubRepositoryService;
import qg.qgent.github.client.GitHubAppClient;
import java.util.UUID;
import java.util.function.Supplier;
/** Agent 选择 App 安装授权或部署凭证；访问凭证不进入模型上下文。 */
@Component
@RequiredArgsConstructor
public class GitHubCapability {
    private final Environment environment;
    private final WorkspaceCommands commands;
    private final GitHubRepositoryService repositories;
    public GitHubTools tools(AgentEntity agent, UUID actor, UUID projectId, Supplier<UUID> workspace) {
        String ref = agent.getGithubCredentialRef();
        if (ref == null || ref.isBlank()) return null;
        if ("github-app".equals(ref)) {
            return new GitHubTools(GitHubAppClient.httpBuilder().build(),
                    repository -> repositories.repositoryToken(actor, projectId, agent.getTeamId(), repository),
                    commands, workspace, () -> repositories.listProjectRepositories(actor, projectId));
        }
        String token = environment.getProperty("app.agent.github." + ref + ".token");
        return new GitHubTools(GitHubAppClient.httpBuilder().build(), token, commands, workspace);
    }
}
