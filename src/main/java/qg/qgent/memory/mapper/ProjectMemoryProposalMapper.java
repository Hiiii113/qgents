package qg.qgent.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import qg.qgent.memory.entity.ProjectMemoryProposalEntity;

import java.util.List;

/** 项目记忆候选数据访问；审批方在本接口外持有项目行锁，候选状态与写入因此按项目串行。 */
@Mapper
public interface ProjectMemoryProposalMapper extends BaseMapper<ProjectMemoryProposalEntity> {

    /** 候选消息上的候选；审批方随后核验项目、来源群与状态。 */
    @Select("SELECT * FROM project_memory_proposals WHERE message_id = #{messageId}")
    ProjectMemoryProposalEntity selectByMessageId(@Param("messageId") Long messageId);

    /** 项目待审批候选的基线版本；基线过时的候选已无法写入，由调用方决定是否还算待审批。 */
    @Select("SELECT base_version FROM project_memory_proposals WHERE project_id = #{projectId} "
            + "AND status = 'PENDING'")
    List<String> selectPendingBaseVersions(@Param("projectId") Long projectId);

    /** 记忆写入成功后标记完成；只在仍待审批时生效，重复审批得到 0。 */
    @Update("UPDATE project_memory_proposals SET status = 'APPLIED', applied_version = #{version}, "
            + "approved_by = #{approvedBy}, approved_message_id = #{approvalMessageId}, "
            + "approved_at = UTC_TIMESTAMP(6) WHERE id = #{id} AND status = 'PENDING'")
    int markApplied(@Param("id") Long id, @Param("approvedBy") Long approvedBy,
                    @Param("approvalMessageId") Long approvalMessageId, @Param("version") String version);

    /** 把候选绑定到运行结束时发出的 Agent 回复消息，审批回复必须指向这条消息。 */
    @Update("UPDATE project_memory_proposals SET message_id = #{messageId} "
            + "WHERE agent_run_id = #{agentRunId} AND status = 'PENDING'")
    int bindMessage(@Param("agentRunId") Long agentRunId, @Param("messageId") Long messageId);
}
