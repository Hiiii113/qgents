package qg.qgent.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.workspace.entity.WorkspaceEntity;

/**
 * 工作区记录与写入租约的数据库操作。
 */
@Mapper
public interface WorkspaceMapper extends BaseMapper<WorkspaceEntity> {
    /**
     * 领取工作区写入租约；旧租约到期或所属运行结束后可接管。
     */
    int claimWriteLease(
        @Param("projectId") Long projectId,
        @Param("workspaceId") Long workspaceId,
        @Param("agentRunId") Long agentRunId,
        @Param("token") String token);

    /**
     * 仅当前持有者可续租；返回 0 表示租约已被接管、过期或 Workspace 归属不匹配。
     */
    int renewWriteLease(
        @Param("projectId") Long projectId,
        @Param("workspaceId") Long workspaceId,
        @Param("agentRunId") Long agentRunId,
        @Param("token") String token);

    /**
     * 仅当前令牌可释放租约，防止旧进程迟到的 finally 清理掉新持有者。
     */
    int releaseWriteLease(
        @Param("projectId") Long projectId,
        @Param("workspaceId") Long workspaceId,
        @Param("agentRunId") Long agentRunId,
        @Param("token") String token);
}
