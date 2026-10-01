package qg.qgent.mergerequest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import qg.qgent.github.client.GitHubAppClient;
import qg.qgent.github.client.GitHubPullRequestCreateRequest;
import qg.qgent.github.client.GitHubPullRequestDetails;
import qg.qgent.github.entity.GitCredentialPurpose;
import qg.qgent.github.entity.GitHubInstallationEntity;
import qg.qgent.github.entity.GitHubRepositoryEntity;
import qg.qgent.github.mapper.GitHubInstallationMapper;
import qg.qgent.github.mapper.GitHubRepositoryMapper;
import qg.qgent.github.service.GitCredentialService;
import qg.qgent.mergerequest.entity.MergeRequestEntity;
import qg.qgent.mergerequest.mapper.MergeRequestMapper;
import qg.qgent.project.entity.ProjectRepositoryEntity;
import qg.qgent.project.mapper.ProjectRepositoryMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.sandbox.client.SandboxWorkerClient;
import qg.qgent.sandbox.dto.WorkerGitCommitResponse;
import qg.qgent.sandbox.dto.WorkerGitDiff;
import qg.qgent.sandbox.dto.WorkerGitPushResponse;
import qg.qgent.task.entity.TaskEntity;
import qg.qgent.task.mapper.TaskMapper;
import qg.qgent.workspace.entity.WorkspaceRepositoryEntity;
import qg.qgent.workspace.mapper.WorkspaceRepositoryMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MergeRequestServiceTest {
    private final UUID projectId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
    private final ProjectRepositoryMapper repositories = mock(ProjectRepositoryMapper.class);
    private final GitHubRepositoryMapper githubRepositories = mock(GitHubRepositoryMapper.class);
    private final GitHubInstallationMapper installations = mock(GitHubInstallationMapper.class);
    private final MergeRequestMapper mirrors = mock(MergeRequestMapper.class);
    private final SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
    private final GitCredentialService credentials = mock(GitCredentialService.class);
    private final GitHubAppClient github = mock(GitHubAppClient.class);
    private final MergeRequestService service = new MergeRequestService(tasks, worktrees, repositories,
            githubRepositories, installations, mirrors, worker, credentials, github,
            mock(ProjectAccessService.class), mock(EventService.class));
    private final GitHubPullRequestDetails pr = new GitHubPullRequestDetails(100L, 7, "open", "修改", "new-head",
            "feat/task", "main", false, "https://github.com/team/repo/pull/7", null, "unknown", "base");

    @BeforeEach
    void setup() {
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setGroupId(UUID.randomUUID());
        task.setWorkspaceId(workspaceId);
        when(tasks.selectById(taskId)).thenReturn(task);
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setRepositoryId(UUID.randomUUID());
        when(repositories.selectById(repositoryId)).thenReturn(repository);
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setProjectRepositoryId(repositoryId);
        worktree.setBaseRef("main");
        worktree.setSourceBranch("feat/task");
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        GitHubRepositoryEntity remote = new GitHubRepositoryEntity();
        remote.setInstallationId(UUID.randomUUID());
        remote.setOwnerLogin("team");
        remote.setName("repo");
        when(githubRepositories.selectById(repository.getRepositoryId())).thenReturn(remote);
        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setTeamId(UUID.randomUUID());
        installation.setProviderInstallationId(99L);
        when(installations.selectById(remote.getInstallationId())).thenReturn(installation);
        when(credentials.generateGrant(any(), eq(projectId), eq(99L), eq("team/repo"), eq("feat/task"),
                anyString(), eq(GitCredentialPurpose.PUSH))).thenReturn("grant");
        when(worker.createWorkspaceGitDiff(workspaceId, repositoryId))
                .thenReturn(new WorkerGitDiff("old-head", "hash", "patch"));
        WorkerGitCommitResponse committed = new WorkerGitCommitResponse();
        committed.setCommitSha("new-head");
        when(worker.commitWorkspaceDiff(eq(workspaceId), eq(repositoryId), any())).thenReturn(committed);
        when(worker.pushWorkspaceBranch(eq(workspaceId), eq(repositoryId), any()))
                .thenReturn(new WorkerGitPushResponse().setHeadCommit("new-head").setVerified(true));
        when(github.createPullRequest(eq(99L), eq("team"), eq("repo"), any())).thenReturn(pr);
    }

    @Test
    void commitsPushesAndCreatesGithubPrWithoutAdditionalGate() {
        MergeRequestEntity result = service.create(projectId, actor, taskId, repositoryId, null, "修改", "说明");
        var order = inOrder(worker, github);
        order.verify(worker).createWorkspaceGitDiff(workspaceId, repositoryId);
        order.verify(worker).commitWorkspaceDiff(eq(workspaceId), eq(repositoryId), argThat(request ->
                "old-head".equals(request.getExpectedHeadCommit()) && "hash".equals(request.getExpectedDiffHash())));
        order.verify(worker).pushWorkspaceBranch(eq(workspaceId), eq(repositoryId), argThat(request ->
                "new-head".equals(request.getExpectedHeadCommit()) && "grant".equals(request.getCredentialGrantId())));
        order.verify(github).findOpenPullRequest(99L, "team", "repo", "feat/task", "main");
        order.verify(github).createPullRequest(99L, "team", "repo",
                new GitHubPullRequestCreateRequest("修改", "说明", "feat/task", "main"));
        assertThat(result.getHtmlUrl()).isEqualTo(pr.htmlUrl());
        assertThat(result.getTaskId()).isEqualTo(taskId);
        verify(mirrors).insert(result);
    }

    @Test
    void cleanWorktreeReusesExistingPrWithoutAnotherCommit() {
        when(worker.createWorkspaceGitDiff(workspaceId, repositoryId))
                .thenReturn(new WorkerGitDiff("new-head", "hash", ""));
        when(github.findOpenPullRequest(99L, "team", "repo", "feat/task", "main")).thenReturn(pr);
        assertThat(service.create(projectId, actor, taskId, repositoryId, null, "修改", "说明").getHtmlUrl())
                .isEqualTo(pr.htmlUrl());
        verify(worker, never()).commitWorkspaceDiff(any(), any(), any());
        verify(github, never()).createPullRequest(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void failedPushDoesNotCreatePrOrMirror() {
        when(worker.pushWorkspaceBranch(eq(workspaceId), eq(repositoryId), any()))
                .thenReturn(new WorkerGitPushResponse().setVerified(false));
        assertThatThrownBy(() -> service.create(projectId, actor, taskId, repositoryId, null, "修改", "说明"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(github, mirrors);
    }
}
