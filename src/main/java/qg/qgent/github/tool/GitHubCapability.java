package qg.qgent.github.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import qg.qgent.github.client.GitHubAppClient;
import qg.qgent.github.service.GitHubRepositoryService;
import qg.qgent.sandbox.service.SandboxCommands;

import java.util.function.Supplier;

/** 提供 GitHub 工具；每个工具调用时重新核对项目导入关系并取得单仓库短期凭证。 */
@Component
@RequiredArgsConstructor
public class GitHubCapability {
    private final SandboxCommands commands;
    private final GitHubRepositoryService repositories;

    public GitHubTools tools(Long actor, Long projectId, Supplier<Long> workspace) {
        return new GitHubTools(GitHubAppClient.httpBuilder().build(), repositories, actor, projectId, commands, workspace);
    }
}
