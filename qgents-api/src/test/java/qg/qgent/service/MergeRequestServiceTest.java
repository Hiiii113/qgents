package qg.qgent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubPullRequestCreateRequest;
import qg.qgent.github.GitHubPullRequestDetails;
import qg.qgent.entity.GitCredentialPurpose;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.service.GitCredentialService;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.service.ProjectAccessService;
import qg.qgent.service.EventService;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitCommitResponse;
import qg.qgent.orchestration.worker.WorkerGitDiff;
import qg.qgent.orchestration.worker.WorkerGitPushResponse;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.WorkspaceRepositoryMapper;

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
        task.setRequirementGroupId(UUID.randomUUID());
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
