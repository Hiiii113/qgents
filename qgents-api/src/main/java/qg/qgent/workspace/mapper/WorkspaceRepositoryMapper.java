package qg.qgent.workspace.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import qg.qgent.workspace.entity.WorkspaceRepositoryEntity;

import java.util.List;
import java.util.UUID;

/**
 * Data access for composite-key Workspace repository worktrees.
 */
@Mapper
public interface WorkspaceRepositoryMapper {
    /**
     * Persists a new repository worktree owned by a Workspace.
     * 入参 baseRef 为创建时指定的基线分支名，写入不可变 base_ref 列；
     * base_commit 留空，待 Worker provision 回填真实 SHA。
     */
    @Insert("insert into workspace_repositories(workspace_id,project_repository_id,workspace_path,base_ref,source_branch) "
            + "values(#{workspaceId},#{repositoryId},#{workspacePath},#{baseRef},#{sourceBranch})")
    int insertLink(@Param("workspaceId") UUID workspaceId, @Param("repositoryId") UUID repositoryId,
                   @Param("workspacePath") String workspacePath, @Param("baseRef") String baseRef,
                   @Param("sourceBranch") String sourceBranch);

    /**
     * Lists all repository worktrees available in a Workspace.
     */
    @Select("select workspace_id,project_repository_id,workspace_path,base_commit,base_ref,source_branch,head_commit,created_at,updated_at "
            + "from workspace_repositories where workspace_id=#{workspaceId}")
    List<WorkspaceRepositoryEntity> selectByWorkspace(UUID workspaceId);

    /**
     * Writes the real commit created by the controlled Worker.
     */
    @org.apache.ibatis.annotations.Update("update workspace_repositories set head_commit=#{headCommit} "
            + "where workspace_id=#{workspaceId} and project_repository_id=#{repositoryId}")
    int updateHeadCommit(@Param("workspaceId") UUID workspaceId, @Param("repositoryId") UUID repositoryId,
                         @Param("headCommit") String headCommit);

    /**
     * 固化 Worker provision 返回的真实基线/HEAD 提交。base_commit 专存 SHA；
     * base_ref 为空时用 Worker 回报的基线分支名回填，已有值不覆盖。
     */
    @org.apache.ibatis.annotations.Update("update workspace_repositories set base_commit=#{baseCommit}, "
            + "head_commit=#{headCommit}, base_ref=coalesce(nullif(base_ref,''), #{baseRef}) "
            + "where workspace_id=#{workspaceId} and project_repository_id=#{repositoryId}")
    int updateCommits(@Param("workspaceId") UUID workspaceId, @Param("repositoryId") UUID repositoryId,
                      @Param("baseCommit") String baseCommit, @Param("headCommit") String headCommit,
                      @Param("baseRef") String baseRef);
}
