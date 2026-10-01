package qg.qgent.workspace.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import qg.qgent.workspace.dto.ExecutionResult;
import qg.qgent.workspace.dto.GitDiffResult;
import qg.qgent.workspace.entity.WorkspaceRepositoryEntity;
import qg.qgent.workspace.mapper.WorkspaceRepositoryMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 在工作区的各个仓库中执行 git diff，汇总实际修改和提交标识。 */
@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "false", matchIfMissing = true)
public class LocalGitDiffAccess implements WorkspaceDiffAccess {

    /**
     * 单次 git 命令超时。
     */
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    private final WorkspaceService workspaceService;
    private final WorkspaceRepositoryMapper repositoryMapper;
    private final SandboxProcessRunner processRunner;

    @Autowired
    public LocalGitDiffAccess(WorkspaceService workspaceService, WorkspaceRepositoryMapper repositoryMapper) {
        this(workspaceService, repositoryMapper, new SandboxProcessRunner());
    }

    /**
     * 测试用构造：可注入 Runner 桩，Workspace 定位与 worktree 查询仍为真实依赖。
     */
    LocalGitDiffAccess(WorkspaceService workspaceService, WorkspaceRepositoryMapper repositoryMapper,
                       SandboxProcessRunner processRunner) {
        this.workspaceService = workspaceService;
        this.repositoryMapper = repositoryMapper;
        this.processRunner = processRunner;
    }

    @Override
    public GitDiffResult diff(UUID workspaceId) {
        WorkspaceService.WorkspaceResolution resolution = workspaceService.resolve(workspaceId);
        if (!resolution.ready() || resolution.root() == null) {
            return failure("workspace is not ready for git diff: "
                    + (resolution.reason() == null ? "unknown" : resolution.reason()));
        }
        Path root = resolution.root();
        List<WorkspaceRepositoryEntity> worktrees = repositoryMapper.selectByWorkspace(workspaceId);
        if (worktrees == null || worktrees.isEmpty()) {
            return failure("workspace has no repository worktrees");
        }
        StringBuilder combined = new StringBuilder();
        String baseCommit = null;
        String headCommit = null;
        for (WorkspaceRepositoryEntity worktree : worktrees) {
            WorktreeDiff worktreeDiff = diffWorktree(root, worktree);
            if (!worktreeDiff.ok()) {
                return failure(worktreeDiff.error());
            }
            if (baseCommit == null) {
                baseCommit = worktreeDiff.baseCommit();
                headCommit = worktreeDiff.headCommit();
            }
            if (!worktreeDiff.diff().isBlank()) {
                if (combined.length() > 0) {
                    combined.append('\n');
                }
                combined.append("===== ").append(worktree.getWorkspacePath()).append(" =====\n")
                        .append(worktreeDiff.diff());
            }
        }
        return GitDiffResult.ok(combined.toString(), baseCommit, headCommit);
    }

    /**
     * 在单个 worktree 上执行 git diff + rev-parse，返回 diff 文本与真实 base/head SHA。
     */
    private WorktreeDiff diffWorktree(Path root, WorkspaceRepositoryEntity worktree) {
        String workspacePath = worktree.getWorkspacePath();
        Path repoDir = resolveWorktree(root, workspacePath);
        if (repoDir == null) {
            return WorktreeDiff.failure("worktree path escapes workspace root: " + workspacePath);
        }
        String base = baseRef(worktree);
        if (!GitDiffCommandBuilder.isValidBase(base)) {
            return WorktreeDiff.failure("worktree has no valid base ref");
        }
        if (!Files.isDirectory(repoDir)) {
            return WorktreeDiff.failure("worktree directory not present: " + repoDir);
        }
        ExecutionResult diff = processRunner.run(repoDir, GitDiffCommandBuilder.diffCommand(base), GIT_TIMEOUT);
        if (!diff.ok()) {
            return WorktreeDiff.failure("git diff failed to run: " + diff.error());
        }
        if (diff.exitCode() != 0) {
            return WorktreeDiff.failure("git diff failed (exit " + diff.exitCode() + "): " + firstLine(diff.stderr()));
        }
        ExecutionResult baseRev = processRunner.run(repoDir, GitDiffCommandBuilder.revParseCommand(base), GIT_TIMEOUT);
        ExecutionResult headRev = processRunner.run(repoDir, GitDiffCommandBuilder.revParseCommand("HEAD"), GIT_TIMEOUT);
        if (!baseRev.ok() || baseRev.exitCode() != 0 || !headRev.ok() || headRev.exitCode() != 0) {
            return WorktreeDiff.failure("git rev-parse failed to resolve base/head");
        }
        String baseSha = baseRev.stdout().trim();
        String headSha = headRev.stdout().trim();
        if (!GitDiffCommandBuilder.isValidBase(baseSha) || !GitDiffCommandBuilder.isValidBase(headSha)) {
            return WorktreeDiff.failure("git rev-parse returned invalid commit");
        }
        return WorktreeDiff.ok(diff.stdout(), baseSha, headSha);
    }

    /**
     * diff base：优先已接受的 headCommit，否则 worktree 创建时的 baseCommit。
     */
    private static String baseRef(WorkspaceRepositoryEntity worktree) {
        String head = worktree.getHeadCommit();
        return (head != null && !head.isBlank()) ? head : worktree.getBaseCommit();
    }

    /**
     * 把 DB 中的相对 worktree 名解析到工作区根目录下，越界/非法时返回 null。
     */
    private static Path resolveWorktree(Path root, String workspacePath) {
        if (workspacePath == null || workspacePath.isBlank()) {
            return null;
        }
        Path repoDir = root.resolve(workspacePath).normalize();
        return repoDir.startsWith(root) ? repoDir : null;
    }

    private static String firstLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int newline = value.indexOf('\n');
        return newline >= 0 ? value.substring(0, newline) : value;
    }

    private static GitDiffResult failure(String error) {
        return GitDiffResult.failure(error);
    }

    /**
     * 单个 worktree 的 diff 结果（内部值对象）。
     */
    private record WorktreeDiff(boolean ok, String diff, String baseCommit, String headCommit, String error) {

        static WorktreeDiff ok(String diff, String baseCommit, String headCommit) {
            return new WorktreeDiff(true, diff, baseCommit, headCommit, null);
        }

        static WorktreeDiff failure(String error) {
            return new WorktreeDiff(false, "", "", "", error);
        }
    }
}
