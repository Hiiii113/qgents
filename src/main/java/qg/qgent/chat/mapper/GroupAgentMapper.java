package qg.qgent.chat.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.chat.mapper.view.GroupAgentRow;

import java.util.List;

/**
 * 群内 Agent 成员；Agent 入群后自动接受邀请，无需额外状态。
 */
@Mapper
public interface GroupAgentMapper {

    int insertAgent(@Param("groupId") Long groupId, @Param("agentId") Long agentId);

    List<GroupAgentRow> selectAgents(@Param("groupId") Long groupId);

    List<Long> selectAgentIds(@Param("groupId") Long groupId);

    List<Long> selectGroupIds(@Param("agentId") Long agentId);

    Long countAgents(@Param("groupId") Long groupId);
}
