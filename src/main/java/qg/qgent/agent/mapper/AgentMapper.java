package qg.qgent.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import qg.qgent.agent.entity.AgentEntity;

import java.util.List;

/** 项目内 Agent 数据访问；申请状态只在服务端按真实审批消息推进。 */
@Mapper
public interface AgentMapper extends BaseMapper<AgentEntity> {

    /** 项目默认 Agent；项目创建时写入，唯一索引保证每项目只有一条。 */
    @Select("SELECT * FROM agents WHERE project_id = #{projectId} AND default_agent = 1")
    AgentEntity selectDefaultAgent(@Param("projectId") Long projectId);

    /** 项目内已启用的 Agent：只有它们能邀请入群和运行。 */
    @Select("SELECT * FROM agents WHERE project_id = #{projectId} AND status = 'ACTIVE' "
            + "ORDER BY default_agent DESC, name")
    List<AgentEntity> selectActiveByProject(@Param("projectId") Long projectId);

    /** 项目内待审批的申请。 */
    @Select("SELECT * FROM agents WHERE project_id = #{projectId} AND status = 'PENDING' ORDER BY created_at, id")
    List<AgentEntity> selectPendingByProject(@Param("projectId") Long projectId);

    /** 计入数量上限的 Agent：待审批申请也算，被拒绝的申请释放名额。 */
    @Select("SELECT COUNT(*) FROM agents WHERE project_id = #{projectId} AND status <> 'REJECTED'")
    int countCountedByProject(@Param("projectId") Long projectId);

    /** 审批通过；只在仍待审批时生效，重复审批得到 0。 */
    @Update("UPDATE agents SET status = 'ACTIVE', approved_by = #{approvedBy}, approved_at = UTC_TIMESTAMP(6) "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int activate(@Param("id") Long id, @Param("approvedBy") Long approvedBy);

    /** 管理员拒绝申请；被拒绝的申请不能激活，也不再占用数量上限。 */
    @Update("UPDATE agents SET status = 'REJECTED', approved_by = #{approvedBy}, approved_at = UTC_TIMESTAMP(6) "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int reject(@Param("id") Long id, @Param("approvedBy") Long approvedBy);

    /** 把申请绑定到公布它的 Agent 回复消息；审批回复必须指向这条消息。 */
    @Update("UPDATE agents SET announced_message_id = #{messageId} "
            + "WHERE id = #{id} AND status = 'PENDING' AND announced_message_id IS NULL")
    int bindAnnouncement(@Param("id") Long id, @Param("messageId") Long messageId);
}
