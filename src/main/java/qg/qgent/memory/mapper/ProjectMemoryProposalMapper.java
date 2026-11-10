package qg.qgent.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import qg.qgent.memory.entity.ProjectMemoryProposalEntity;

/** 项目记忆候选数据访问；状态迁移用带条件的 UPDATE 保证跨实例只有一个审批生效。 */
@Mapper
public interface ProjectMemoryProposalMapper extends BaseMapper<ProjectMemoryProposalEntity> {

    /** 审批回复所指向的消息对应的待审批候选；调用方随后核验项目与来源群。 */
    @Select("SELECT * FROM project_memory_proposals WHERE message_id = #{messageId} AND status = 'PENDING'")
    ProjectMemoryProposalEntity selectPendingByMessageId(@Param("messageId") Long messageId);

    /** 一次运行只能提出一个候选，避免同一条回复对应多个待审批内容。 */
    @Select("SELECT COUNT(*) FROM project_memory_proposals WHERE agent_run_id = #{agentRunId}")
    int countByAgentRunId(@Param("agentRunId") Long agentRunId);

    /**
     * 占住待审批候选：并发审批时只有一个请求能把 PENDING 转成 APPLYING。
     * 返回 0 表示候选已被处理，调用方必须放弃写记忆。
     */
    @Update("UPDATE project_memory_proposals SET status = 'APPLYING', approved_by = #{approvedBy}, "
            + "approved_message_id = #{approvalMessageId} WHERE id = #{id} AND status = 'PENDING'")
    int claim(@Param("id") Long id, @Param("approvedBy") Long approvedBy,
              @Param("approvalMessageId") Long approvalMessageId);

    /** 记忆写入成功后标记完成；只在 APPLYING 状态生效。 */
    @Update("UPDATE project_memory_proposals SET status = 'APPLIED', applied_version = #{version}, "
            + "approved_at = UTC_TIMESTAMP(6) WHERE id = #{id} AND status = 'APPLYING'")
    int markApplied(@Param("id") Long id, @Param("version") String version);

    /** 把候选绑定到运行结束时发出的 Agent 回复消息，审批回复必须指向这条消息。 */
    @Update("UPDATE project_memory_proposals SET message_id = #{messageId} "
            + "WHERE agent_run_id = #{agentRunId} AND status = 'PENDING'")
    int bindMessage(@Param("agentRunId") Long agentRunId, @Param("messageId") Long messageId);
}
