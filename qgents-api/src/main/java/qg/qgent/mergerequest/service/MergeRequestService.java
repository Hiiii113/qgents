package qg.qgent.mergerequest.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import qg.qgent.common.util.UuidV7;
import qg.qgent.common.web.ApiException;
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
import qg.qgent.sandbox.dto.WorkerGitCommitRequest;
import qg.qgent.sandbox.dto.WorkerGitCommitResponse;
import qg.qgent.sandbox.dto.WorkerGitDiff;
import qg.qgent.sandbox.dto.WorkerGitPushRequest;
import qg.qgent.sandbox.dto.WorkerGitPushResponse;
import qg.qgent.task.entity.TaskEntity;
import qg.qgent.task.mapper.TaskMapper;
import qg.qgent.workspace.entity.WorkspaceRepositoryEntity;
import qg.qgent.workspace.mapper.WorkspaceRepositoryMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 用户明确要求提交时，将 Task 工作区的当前修改提交到 GitHub PR。 */
@Service
@RequiredArgsConstructor
public class MergeRequestService {
    private final TaskMapper tasks;
    private final WorkspaceRepositoryMapper worktrees;
    private final ProjectRepositoryMapper repositories;
    private final GitHubRepositoryMapper githubRepositories;
    private final GitHubInstallationMapper installations;
    private final MergeRequestMapper mergeRequests;
    private final SandboxWorkerClient worker;
    private final GitCredentialService credentials;
    private final GitHubAppClient github;
    private final ProjectAccessService access;
    private final EventService events;

    public MergeRequestEntity create(UUID projectId, UUID actor, UUID taskId, UUID repositoryId,
                                     String targetBranch, String title, String body) {
        access.requireProjectMember(projectId, actor);
        TaskEntity task = tasks.selectById(taskId);
        ProjectRepositoryEntity repository = repositories.selectById(repositoryId);
        if (task == null || repository == null || !projectId.equals(task.getProjectId())
                || !projectId.equals(repository.getProjectId())) throw missing();
        WorkspaceRepositoryEntity worktree = worktrees.selectByWorkspace(task.getWorkspaceId()).stream()
                .filter(item -> repositoryId.equals(item.getProjectRepositoryId())).findFirst().orElseThrow(this::missing);
        if (title == null || title.isBlank()) throw new IllegalArgumentException("MR 标题不能为空");
        String target = targetBranch == null || targetBranch.isBlank() ? worktree.getBaseRef() : targetBranch;
        if (target == null || target.isBlank()) target = repository.getDefaultBranch();
        if (target == null || target.isBlank() || target.equals(worktree.getSourceBranch()))
            throw new IllegalArgumentException("请指定有效的目标分支");
        GitHubRepositoryEntity remoteRepository = githubRepositories.selectById(repository.getRepositoryId());
        GitHubInstallationEntity installation = remoteRepository == null ? null
                : installations.selectById(remoteRepository.getInstallationId());
        if (installation == null) throw missing();
        WorkerGitDiff diff = worker.createWorkspaceGitDiff(task.getWorkspaceId(), repositoryId);
        String head = diff.getHeadCommit();
        if (diff.getPatch() != null && !diff.getPatch().isBlank()) {
            WorkerGitCommitResponse committed = worker.commitWorkspaceDiff(task.getWorkspaceId(), repositoryId,
                    new WorkerGitCommitRequest().setExpectedHeadCommit(head).setExpectedDiffHash(diff.getDiffHash())
                            .setMessage(title).setOperationId(UUID.randomUUID().toString()));
            head = committed.getCommitSha();
            worktrees.updateHeadCommit(task.getWorkspaceId(), repositoryId, head);
        }
        if (head == null || head.isBlank()) throw new IllegalStateException("工作区没有可提交的 HEAD");
        String grant = credentials.generateGrant(installation.getTeamId(), projectId,
                installation.getProviderInstallationId(), remoteRepository.getOwnerLogin()+"/"+remoteRepository.getName(),
                worktree.getSourceBranch(), head, GitCredentialPurpose.PUSH);
        WorkerGitPushResponse pushed = worker.pushWorkspaceBranch(task.getWorkspaceId(), repositoryId,
                new WorkerGitPushRequest().setExpectedHeadCommit(head).setCredentialGrantId(grant));
        if (pushed == null || !pushed.isVerified() || !head.equalsIgnoreCase(pushed.getHeadCommit()))
            throw new IllegalStateException("分支推送未成功确认");
        GitHubPullRequestDetails pr = github.findOpenPullRequest(installation.getProviderInstallationId(),
                remoteRepository.getOwnerLogin(), remoteRepository.getName(), worktree.getSourceBranch(), target);
        if (pr == null) pr = github.createPullRequest(installation.getProviderInstallationId(),
                remoteRepository.getOwnerLogin(), remoteRepository.getName(),
                new GitHubPullRequestCreateRequest(title, body, worktree.getSourceBranch(), target));
        MergeRequestEntity record = mergeRequests.selectOne(Wrappers.<MergeRequestEntity>lambdaQuery()
                .eq(MergeRequestEntity::getProjectRepositoryId, repositoryId)
                .eq(MergeRequestEntity::getProviderNumber, pr.number()).last("LIMIT 1"));
        boolean created = record == null;
        if (created) {
            record = new MergeRequestEntity();
            record.setId(UuidV7.next());
        }
        record.setProjectRepositoryId(repositoryId);
        record.setTaskId(taskId);
        record.setWorkspaceId(task.getWorkspaceId());
        record.setProvider("GITHUB");
        record.setProviderNumber((long)pr.number());
        record.setTitle(pr.title());
        record.setSourceBranch(pr.headBranch());
        record.setTargetBranch(pr.baseBranch());
        record.setHeadCommit(pr.headSha());
        record.setStatus(pr.merged() ? "MERGED" : pr.state().toUpperCase());
        record.setHtmlUrl(pr.htmlUrl());
        record.setMergeable(pr.mergeable());
        record.setMergeableState(pr.mergeableState());
        record.setBaseSha(pr.baseSha());
        record.setAuthorUserId(actor);
        record.setSyncedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (created) {
            record.setCreatedAt(record.getSyncedAt());
            mergeRequests.insert(record);
        } else {
            mergeRequests.updateById(record);
        }
        events.publish(projectId, task.getRequirementGroupId(), "merge-request.updated", record.getId().toString(),
                Map.of("mergeRequestId", record.getId(), "taskId", taskId, "status", record.getStatus(), "url", record.getHtmlUrl()));
        return record;
    }

    public List<MergeRequestEntity> list(UUID projectId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        List<UUID> ids = repositories.selectList(Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                .eq(ProjectRepositoryEntity::getProjectId, projectId)).stream().map(ProjectRepositoryEntity::getId).toList();
        return ids.isEmpty() ? List.of() : mergeRequests.selectList(Wrappers.<MergeRequestEntity>lambdaQuery()
                .in(MergeRequestEntity::getProjectRepositoryId, ids).orderByDesc(MergeRequestEntity::getCreatedAt).last("LIMIT 100"));
    }

    public MergeRequestEntity detail(UUID projectId, UUID actor, UUID id) {
        access.requireProjectMember(projectId, actor);
        MergeRequestEntity mr = mergeRequests.selectById(id);
        ProjectRepositoryEntity repository = mr == null ? null : repositories.selectById(mr.getProjectRepositoryId());
        if (repository == null || !projectId.equals(repository.getProjectId())) throw missing();
        return mr;
    }

    private ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "MR_RESOURCE_NOT_FOUND", "任务、仓库或 GitHub 授权不存在");
    }
}
