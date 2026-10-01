package qg.qgent.orchestration.worker;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import qg.qgent.auth.UuidV7;
import qg.qgent.api.ApiException;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubBranchDetails;
import qg.qgent.entity.GitCredentialPurpose;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.service.GitCredentialService;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.SandboxWorkerProperties;
import qg.qgent.orchestration.worker.WorkerCreateSandboxRequest;
import qg.qgent.orchestration.worker.WorkerGitStoreSyncRequest;
import qg.qgent.orchestration.worker.WorkerGitStoreSyncResponse;
import qg.qgent.orchestration.worker.WorkerSandbox;
import qg.qgent.orchestration.worker.WorkerWorkspace;
import qg.qgent.orchestration.worker.WorkerWorkspaceProvisionRequest;
import qg.qgent.orchestration.worker.WorkerWorkspaceRepository;
import qg.qgent.orchestration.worker.WorkerWorkspaceRepositoryRequest;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.WorkspaceMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.service.WorkspaceWriteLease;
import qg.qgent.service.WorkspaceWriteLeaseService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Sandbox 会话生命周期管理：把 Task 编排需要的"一次执行现场"映射为 Worker 的
 * 幂等 Workspace provision + 一次 Sandbox 创建/销毁。
 * <p>
 * 归属与安全边界：
 * <ul>
 *   <li>只通过 {@link WorkspaceMapper}/{@link WorkspaceRepositoryMapper} 读取已在服务端
 *       落库的 workspace 与 worktree，不自行拼接宿主机路径或 Git 远端；</li>
 *   <li>provision 请求只携带资源编号与受控 Git 引用（repositoryId/baseRef/sourceBranch/workspacePath），
 *       不提交凭证；</li>
 *   <li>{@code app.worker.enabled=false} 时本管理器为 no-op（返回 null），编排链路仍走本地端口；</li>
 *   <li>销毁只销毁 Sandbox，Workspace 持久保留。</li>
 * </ul>
 * 会话缓存仅用于定位本进程 Sandbox；真正的跨实例单写者约束由
 * {@link WorkspaceWriteLeaseService} 的持久化 CAS 租约保证；进程内同时使用
 * Workspace 级锁串行化初始化与释放。
 */
@Service
public class SandboxSessionManager {

    private static final Pattern COMMIT_SHA_PATTERN = Pattern.compile("[0-9a-fA-F]{40,64}");

    private final SandboxWorkerClient client;
    private final SandboxWorkerProperties properties;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceRepositoryMapper repositoryMapper;
    private final ProjectRepositoryMapper projectRepositoryMapper;
    private final GitHubRepositoryMapper gitHubRepositoryMapper;
    private final GitHubInstallationMapper installationMapper;
    private final GitCredentialService credentialService;
    private final GitHubAppClient githubAppClient;
    private final WorkspaceWriteLeaseService writeLeases;
    private final Map<UUID, SandboxSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, ReentrantLock> acquireLocks = new ConcurrentHashMap<>();
    private final Map<UUID, WorkspaceWriteLease> workspaceLeases = new ConcurrentHashMap<>();

    public SandboxSessionManager(SandboxWorkerClient client, SandboxWorkerProperties properties,
                                 WorkspaceMapper workspaceMapper, WorkspaceRepositoryMapper repositoryMapper,
                                 ProjectRepositoryMapper projectRepositoryMapper, GitHubRepositoryMapper gitHubRepositoryMapper,
                                 GitHubInstallationMapper installationMapper, GitCredentialService credentialService,
                                 GitHubAppClient githubAppClient, WorkspaceWriteLeaseService writeLeases) {
        this.client = client;
        this.properties = properties;
        this.workspaceMapper = workspaceMapper;
        this.repositoryMapper = repositoryMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.gitHubRepositoryMapper = gitHubRepositoryMapper;
        this.installationMapper = installationMapper;
        this.credentialService = credentialService;
        this.githubAppClient = githubAppClient;
        this.writeLeases = writeLeases;
    }

    /**
     * 为一次 Task 编排准备 Sandbox 会话；已存在则直接返回。
     * 未启用 Worker 时返回 null（本地端口不需要会话）。
     */
    public SandboxSession acquire(UUID taskId, UUID projectId, UUID workspaceId) {
        if (!properties.isEnabled()) {
            return null;
        }

        ReentrantLock acquireLock = acquireLocks.computeIfAbsent(workspaceId, ignored -> new ReentrantLock());
        // 进程内锁只用于串行化初始化/释放，不能让编排线程无限等待。持锁者可能正卡在
        // Worker/GitHub 请求；把冲突交给既有 PENDING/恢复器路径，避免 Task 已 RUNNING
        // 但永远没有 Sandbox 或 AgentRun。
        if (!acquireLock.tryLock()) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "WORKSPACE_WRITE_LEASE_HELD", "Workspace is currently being initialized by another Task");
        }
        try {
            SandboxSession existing = sessions.get(workspaceId);
            if (existing != null) {

                if (!existing.taskId().equals(taskId)) {
                    throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                            "WORKSPACE_WRITE_LEASE_HELD", "Workspace is currently being modified by another Task");
                }
                return existing;
            }
            WorkspaceWriteLease lease = writeLeases.acquire(projectId, workspaceId, taskId);
            try {
                SandboxSession created = doAcquire(taskId, projectId, workspaceId);
                sessions.put(workspaceId, created);
                workspaceLeases.put(workspaceId, lease);
                return created;
            } catch (RuntimeException failure) {
                writeLeases.release(lease);
                throw failure;
            }
        } finally {
            acquireLock.unlock();
        }
    }

    /**
     * 返回指定 Workspace 的当前会话；不存在时抛错，供 Worker 端口在调用工具前断言。
     */
    public SandboxSession require(UUID workspaceId) {
        SandboxSession session = sessions.get(workspaceId);
        if (session == null) {
            throw new IllegalStateException("no sandbox session for workspace " + workspaceId);
        }
        return session;
    }

    /**
     * 销毁会话对应的 Sandbox 并移除记录；Workspace 保留。销毁失败不吞结果，仅不阻断任务收尾。
     */
    public void release(UUID workspaceId, UUID taskId) {
        if (workspaceId == null || taskId == null) {
            return;
        }
        ReentrantLock acquireLock = acquireLocks.computeIfAbsent(workspaceId, ignored -> new ReentrantLock());
        // acquire() 失败后编排器仍会进入 finally；释放路径不能再次阻塞在同一把卡死的锁上。
        // 持锁者完成后会负责自己的会话/租约清理，当前 Task 没有可安全移除的会话时直接返回。
        if (!acquireLock.tryLock()) {
            return;
        }
        try {
            SandboxSession session = sessions.get(workspaceId);
            if (session == null || !taskId.equals(session.taskId())) {
                // acquire 可能因其他 Task 已持有会话而失败；只能清理自己的会话。
                return;
            }
            WorkspaceWriteLease lease = workspaceLeases.get(workspaceId);
            if (lease != null && !taskId.equals(lease.getTaskId())) {
                // 缓存不一致时宁可保留可能有效的租约，也不能释放其他 Task 的租约。
                return;
            }
            if (!sessions.remove(workspaceId, session)) {
                return;
            }
            if (lease != null) {
                workspaceLeases.remove(workspaceId, lease);
            }
            try {
                client.destroySandbox(session.sandboxId());
            } catch (RuntimeException ignored) {
                // 销毁失败由 Worker 的清理任务兜底，不阻断任务结果返回。
            } finally {
                writeLeases.release(lease);
            }
        } finally {
            acquireLock.unlock();
        }
    }

    /**
     * 每次 Worker 工具提交前验证并续租持久化写入权。租约已丢失时不能再向同一 Workspace
     * 发起命令、文件写入或测试，避免过期 Task 与后来者并发修改文件。
     */
    void renewWriteLease(UUID workspaceId) {
        WorkspaceWriteLease lease = workspaceLeases.get(workspaceId);
        SandboxSession session = sessions.get(workspaceId);
        if (lease == null || session == null || !session.taskId().equals(lease.getTaskId())) {
            throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "WORKSPACE_WRITE_LEASE_LOST", "Workspace write lease is no longer active");
        }

        writeLeases.renew(lease);
    }

    /**
     * 在整个 Task 会话存活期间维持 Sandbox 租约，覆盖 LLM 推理等没有工具调用的空档。
     * 单个 Sandbox 的续租失败不影响其他会话；实际工具调用会继续得到 Worker 的明确错误。
     */
    @Scheduled(fixedDelayString = "${app.worker.lease-renew-interval:10s}")
    void renewActiveLeases() {
        if (!properties.isEnabled()) {
            return;
        }
        sessions.forEach((workspaceId, session) -> {
            if (sessions.get(workspaceId) != session) {
                return;
            }
            try {
                renewWriteLease(workspaceId);
                client.renewSandbox(session.sandboxId());
            } catch (RuntimeException ignored) {
                // 当前工具调用会传播 Worker 错误；心跳不应阻塞其他活跃 Sandbox 的续租。
            }
        });
    }

    private SandboxSession doAcquire(UUID taskId, UUID projectId, UUID workspaceId) {
        WorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null || !projectId.equals(workspace.getProjectId())) {
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "WORKSPACE_NOT_FOUND", "Workspace does not exist or is not visible");
        }
        List<WorkspaceRepositoryEntity> repositories = repositoryMapper.selectByWorkspace(workspaceId);
        if (repositories == null || repositories.isEmpty()) {
            throw new IllegalStateException("workspace has no repository worktrees: " + workspaceId);
        }

        // 阶段一：同步所有 Git Store 并幂等准备 Workspace，瞬态失败在此阶段内自动重试。
        boolean provisioned = repositories.stream().allMatch(repository -> isCommitSha(repository.getBaseCommit()));
        String storageKey = provisioned ? client.getWorkspace(workspaceId).getStorageKey()
                : prepareWorkspace(projectId, workspaceId, workspace, repositories);

        // 阶段二：创建 Sandbox，整个 acquire 只生成一次 sandboxId，创建请求超时后按同 id 幂等恢复。
        UUID sandboxId = UuidV7.next();
        WorkerCreateSandboxRequest create = new WorkerCreateSandboxRequest();
        create.setSandboxId(sandboxId);
        create.setTaskId(taskId);
        create.setWorkspaceStorageKey(storageKey);
        create.setImageProfile(properties.getImageProfile());
        create.setRepositoryIds(repositories.stream().map(WorkspaceRepositoryEntity::getProjectRepositoryId).toList());
        createSandboxRetry(create);

        Map<String, UUID> repositoryByPath = new LinkedHashMap<>();
        for (WorkspaceRepositoryEntity repository : repositories) {
            repositoryByPath.put(repository.getWorkspacePath(), repository.getProjectRepositoryId());
        }
        return new SandboxSession(taskId, workspaceId, sandboxId, storageKey,
                create.getRepositoryIds(), Collections.unmodifiableMap(new LinkedHashMap<>(repositoryByPath)));
    }

    /**
     * 阶段一：同步所有仓库的 Git Store 并幂等准备 Workspace，返回 storageKey。
     * 整个过程至多尝试 {@code acquireMaxAttempts} 次；每次尝试都重新查询 GitHub 分支 HEAD、
     * 重新生成短期 FETCH credential，并对 sync 返回值做一致性校验。可重试错误退避后重试，
     * 不可重试错误或重试耗尽时抛出最终异常。
     */
    private String prepareWorkspace(UUID projectId, UUID workspaceId, WorkspaceEntity workspace,
                                    List<WorkspaceRepositoryEntity> repositories) {
        int attempts = properties.acquireMaxAttempts();
        long initialBackoffMillis = properties.acquireInitialBackoff().toMillis();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return prepareWorkspaceOnce(projectId, workspaceId, workspace, repositories);
            } catch (ApiException failure) {
                if (!isRetryable(failure) || attempt >= attempts) {
                    throw failure;
                }
                try {
                    sleepBackoff(initialBackoffMillis, attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("sandbox acquire interrupted during retry backoff", interrupted);
                }
            }
        }
        throw new IllegalStateException("unreachable: sandbox workspace prepare exhausted retries");
    }

    /**
     * 单次尝试：GitHub 基线查询 → 逐仓库生成短期 FETCH 凭证并同步 Git Store → 准备 Workspace。
     * 返回 Worker provision 提供的 storageKey（缺省回退到 workspace 持久字段）。
     */
    private String prepareWorkspaceOnce(UUID projectId, UUID workspaceId, WorkspaceEntity workspace,
                                        List<WorkspaceRepositoryEntity> repositories) {
        // 一次 acquire 对每个仓库只解析一次基线分支。同步 Git Store 与后续创建 worktree
        // 必须使用同一个值，避免任务指定基线与项目默认分支不同时出现“已同步 main、却从 develop 建树”。
        Map<UUID, String> baseRefByRepository = new HashMap<>();
        // Fetch Grants and Sync bare Git Stores for each repository
        for (WorkspaceRepositoryEntity repository : repositories) {
            ProjectRepositoryEntity projectRepo = projectRepositoryMapper.selectById(repository.getProjectRepositoryId());
            if (projectRepo == null || !"ACTIVE".equals(projectRepo.getStatus())) {
                throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "PROJECT_REPOSITORY_NOT_BOUND",
                        "Repository binding is not active for workspace repository");
            }
            String configuredBaseRef = repository.getBaseRef();
            String defaultBranch = projectRepo.getDefaultBranch();
            String remoteBranch = firstNonBlank(configuredBaseRef, defaultBranch);
            if (remoteBranch == null || remoteBranch.isBlank()) {
                throw new IllegalStateException("project repository has no default branch: " + repository.getProjectRepositoryId());
            }
            baseRefByRepository.put(repository.getProjectRepositoryId(), remoteBranch);

            GitHubRepositoryEntity ghRepo = gitHubRepositoryMapper.selectById(projectRepo.getRepositoryId());
            if (ghRepo == null) {
                throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "GITHUB_REPOSITORY_NOT_FOUND",
                        "GitHub repository mirror not found");
            }
            GitHubInstallationEntity installation = installationMapper.selectById(ghRepo.getInstallationId());
            if (installation == null) {
                throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "GITHUB_INSTALLATION_NOT_FOUND",
                        "GitHub App installation not found for repository");
            }
            if (!"ACTIVE".equalsIgnoreCase(installation.getStatus()) || installation.getProviderInstallationId() == null) {
                throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_INSTALLATION_NOT_ACTIVE",
                        "GitHub App installation is not active");
            }
            if (!"AUTHORIZED".equalsIgnoreCase(ghRepo.getAuthorizationStatus())) {
                throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "GITHUB_REPOSITORY_REVOKED",
                        "GitHub repository authorization has been revoked");
            }

            // 已 provision 的行（base_commit 为 SHA）沿用钉扎的基线提交，重试不因分支推进漂移；
            // 否则按基线分支实时解析远端 HEAD。
            String expectedHeadCommit = isCommitSha(repository.getBaseCommit()) ? repository.getBaseCommit() : null;
            if (!isCommitSha(expectedHeadCommit)) {
                String owner = ghRepo.getOwnerLogin();
                String repoName = ghRepo.getName();
                GitHubBranchDetails branchDetails;
                try {
                    branchDetails = githubAppClient.getBranch(
                            installation.getProviderInstallationId(), owner, repoName, remoteBranch);
                } catch (ApiException branchFailure) {
                    // 基线分支不存在是用户可修复的确定性错误：保留稳定码与仓库/分支上下文，
                    // 供 Agent 工具结果说明「修改基线分支后重试」，不降级为泛化错误。
                    if ("GIT_BRANCH_NOT_FOUND".equals(branchFailure.code())) {
                        throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                                "GIT_BRANCH_NOT_FOUND",
                                "仓库 " + owner + "/" + repoName + " 不存在基线分支 " + remoteBranch,
                                List.of(Map.of("repository", owner + "/" + repoName,
                                        "branch", remoteBranch, "fullName", owner + "/" + repoName)));
                    }
                    throw branchFailure;
                }
                expectedHeadCommit = branchDetails == null ? null : branchDetails.commitSha();
                if (!isCommitSha(expectedHeadCommit)) {
                    throw new IllegalStateException("GitHub branch did not return a valid HEAD commit: " + remoteBranch);
                }
            }

            String fullName = ghRepo.getOwnerLogin() + "/" + ghRepo.getName();
            String githubUrl = "https://github.com/" + fullName + ".git";

            String grantId = credentialService.generateGrant(installation.getTeamId(), projectId, installation.getProviderInstallationId(),
                    fullName, remoteBranch, expectedHeadCommit, GitCredentialPurpose.FETCH);

            WorkerGitStoreSyncRequest syncReq = new WorkerGitStoreSyncRequest()
                    .setRepositoryUrl(githubUrl)
                    .setRemoteBranch(remoteBranch)
                    .setExpectedHeadCommit(expectedHeadCommit)
                    .setCredentialGrantId(grantId);

            WorkerGitStoreSyncResponse synced = client.syncGitStore(projectRepo.getId(), syncReq);
            validateSyncResult(projectRepo.getId(), remoteBranch, expectedHeadCommit, synced);
        }

        WorkerWorkspaceProvisionRequest provision = new WorkerWorkspaceProvisionRequest();
        provision.setProjectId(projectId);
        provision.setRepositories(repositories.stream()
                .map(repository -> toRepositoryRequest(repository,
                        baseRefByRepository.get(repository.getProjectRepositoryId())))
                .toList());
        WorkerWorkspace provisioned = client.provisionWorkspace(workspaceId, provision);
        persistProvisionedCommits(repositories, provisioned);
        return provisioned != null && provisioned.getStorageKey() != null
                ? provisioned.getStorageKey() : workspace.getStorageKey();
    }

    /**
     * 校验 Git Store 同步结果是否命中预期的仓库、分支与 HEAD；任何一项不匹配视为同步失真，
     * 作为可重试错误抛出，让下一轮重新查询 HEAD 并重新同步。
     */
    private void validateSyncResult(UUID repositoryId, String remoteBranch, String expectedHeadCommit,
                                    WorkerGitStoreSyncResponse synced) {
        if (synced == null || !repositoryId.equals(synced.getRepositoryId())
                || !remoteBranch.equals(synced.getRemoteBranch())
                || !expectedHeadCommit.equalsIgnoreCase(synced.getHeadCommit())) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "GIT_STORE_SYNC_INVALID",
                    "Git store sync result did not match requested repository/branch/head");
        }
    }

    /**
     * 阶段二：创建 Sandbox，并在「创建请求超时、但请求可能已在 Worker 端生效」时按相同
     * sandboxId 查询幂等恢复。Worker 端对相同规格幂等返回，不同规格返回 {@code SANDBOX_ID_CONFLICT}。
     */
    private void createSandboxRetry(WorkerCreateSandboxRequest create) {
        int attempts = properties.acquireMaxAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                client.createSandbox(create);
                return;
            } catch (ApiException failure) {
                if (!"SANDBOX_WORKER_UNAVAILABLE".equals(failure.code())) {
                    throw failure;
                }
                WorkerSandbox existing = querySandboxOrNull(create.getSandboxId());
                if (existing != null && sameSandboxSpec(create, existing)) {
                    return;
                }
                if (existing != null) {
                    throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "SANDBOX_ID_CONFLICT",
                            "sandbox already exists with a different spec");
                }
                if (attempt >= attempts) {
                    throw failure;
                }
                try {
                    sleepBackoff(properties.acquireInitialBackoff().toMillis(), attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("sandbox create interrupted during retry backoff", interrupted);
                }
            }
        }
        throw new IllegalStateException("unreachable: sandbox create exhausted retries");
    }

    private WorkerSandbox querySandboxOrNull(UUID sandboxId) {
        try {
            return client.getSandbox(sandboxId);
        } catch (ApiException failure) {
            if ("SANDBOX_NOT_FOUND".equals(failure.code())
                    || "SANDBOX_WORKER_UNAVAILABLE".equals(failure.code())) {
                return null;
            }
            throw failure;
        }
    }

    private boolean sameSandboxSpec(WorkerCreateSandboxRequest request, WorkerSandbox existing) {
        return Objects.equals(request.getTaskId(), existing.getTaskId())

                && Objects.equals(request.getWorkspaceStorageKey(), existing.getWorkspaceStorageKey())
                && Objects.equals(request.getImageProfile(), existing.getImageProfile())
                && existing.getRepositoryIds() != null
                && new HashSet<>(request.getRepositoryIds()).equals(new HashSet<>(existing.getRepositoryIds()));
    }

    private void sleepBackoff(long initialBackoffMillis, int attempt) throws InterruptedException {
        long multiplier = 1L << Math.min(attempt - 1, 30);
        long cappedInitial = Math.min(30_000L, initialBackoffMillis);
        long backoff = cappedInitial > 30_000L / multiplier
                ? 30_000L : cappedInitial * multiplier;
        long jitter = backoff == 0 ? 0 : ThreadLocalRandom.current().nextLong(Math.max(1L, backoff / 4));
        Thread.sleep(Math.min(30_000L, backoff + jitter));
    }

    /**
     * 判断初始化阶段的失败是否可安全自动重试。传输层不可达与可再同步的瞬态/基线漂移错误可重试；
     * 授权、参数、规格冲突等确定性错误直接失败，不浪费重试。
     */
    private boolean isRetryable(ApiException failure) {
        return switch (failure.code()) {
            case "SANDBOX_WORKER_UNAVAILABLE", "GITHUB_API_UNAVAILABLE", "GIT_REMOTE_NETWORK_FAILED",
                    "GIT_REMOTE_RATE_LIMITED", "GIT_REMOTE_SHA_MISMATCH", "GIT_COMMAND_TIMEOUT" -> true;
            default -> false;
        };
    }

    /**
     * 用 Worker provision 返回的真实基线/HEAD 提交回填 Workspace repository 持久字段。
     * base_commit 专存真实 commit SHA 供最终 Diff 校验比对；不可变基线分支名存 base_ref
     * 。
     */
    private void persistProvisionedCommits(List<WorkspaceRepositoryEntity> repositories, WorkerWorkspace provisioned) {
        if (provisioned == null || provisioned.getRepositories() == null) {
            return;
        }
        for (WorkerWorkspaceRepository provisionedRepo : provisioned.getRepositories()) {
            for (WorkspaceRepositoryEntity repository : repositories) {
                if (repository.getProjectRepositoryId().equals(provisionedRepo.getRepositoryId())
                        && provisionedRepo.getBaseCommit() != null && !provisionedRepo.getBaseCommit().isBlank()) {
                    repository.setBaseCommit(provisionedRepo.getBaseCommit());
                    repository.setHeadCommit(provisionedRepo.getHeadCommit());
                    if ((repository.getBaseRef() == null || repository.getBaseRef().isBlank())
                            && provisionedRepo.getBaseRef() != null && !provisionedRepo.getBaseRef().isBlank()) {
                        repository.setBaseRef(provisionedRepo.getBaseRef());
                    }
                    repositoryMapper.updateCommits(repository.getWorkspaceId(), repository.getProjectRepositoryId(),
                            provisionedRepo.getBaseCommit(), provisionedRepo.getHeadCommit(), repository.getBaseRef());
                    break;
                }
            }
        }
    }

    private WorkerWorkspaceRepositoryRequest toRepositoryRequest(WorkspaceRepositoryEntity repository, String baseRef) {
        if (baseRef == null || baseRef.isBlank()) {
            throw new IllegalStateException("workspace repository has no resolved base ref: "
                    + repository.getProjectRepositoryId());
        }
        WorkerWorkspaceRepositoryRequest request = new WorkerWorkspaceRepositoryRequest();
        request.setRepositoryId(repository.getProjectRepositoryId());
        request.setBaseRef(baseRef);
        request.setSourceBranch(repository.getSourceBranch());
        request.setWorkspacePath(repository.getWorkspacePath());
        return request;
    }

    private boolean isCommitSha(String value) {
        return value != null && COMMIT_SHA_PATTERN.matcher(value).matches();
    }

    private String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }
}
