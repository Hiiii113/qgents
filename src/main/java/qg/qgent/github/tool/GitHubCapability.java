package qg.qgent.github.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.workspace.service.WorkspaceCommands;
import qg.qgent.github.service.GitHubRepositoryService;
import qg.qgent.github.client.GitHubAppClient;

import java.util.function.Supplier;

/** 按项目开关提供 GitHub 工具，使用当前项目导入仓库的 App 安装凭证。 */
@Component
@RequiredArgsConstructor
public class GitHubCapability {
    private final ProjectMapper projects;
    private final WorkspaceCommands commands;
    private final GitHubRepositoryService repositories;

    public GitHubTools tools(Long actor, Long projectId, Supplier<Long> workspace) {
        return new GitHubTools(GitHubAppClient.httpBuilder().build(),
                repository -> repositories.repositoryToken(actor, projectId, enabledProject(projectId).getTeamId(), repository),
                commands, workspace, () -> {
                    enabledProject(projectId);
                    return repositories.listProjectRepositories(actor, projectId);
                });
    }

    private ProjectEntity enabledProject(Long projectId) {
        ProjectEntity project = projects.selectById(projectId);
        if (project == null) throw new IllegalArgumentException("项目不存在");
        if (!Boolean.TRUE.equals(project.getGithubEnabled()))
            throw new IllegalStateException("当前项目已关闭 GitHub 能力");
        return project;
    }
}
