package qg.qgent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.chat.entity.DirectConversationEntity;

import java.util.List;

/**
 * 私聊会话参与者数据访问；会话本身的消息与已读仍走群聊表。
 */
@Mapper
public interface DirectConversationMapper extends BaseMapper<DirectConversationEntity> {

    /**
     * 当前用户在某项目里参与的私聊；参与双方各有一条可见记录，查询按用户过滤。
     */
    @Select(
        "SELECT * FROM direct_conversations WHERE project_id = #{projectId} "
            + "AND ((participant_a_type = 'USER' AND participant_a_id = #{userId}) "
            + "OR (participant_b_type = 'USER' AND participant_b_id = #{userId}))")
    List<DirectConversationEntity> listForUser(
        @Param("projectId") Long projectId, @Param("userId") Long userId);

    /**
     * 该 Agent 是否为这条私聊的对端；Agent 只能回复自己作为参与者的会话。
     */
    @Select(
        "SELECT COUNT(*) FROM direct_conversations WHERE group_id = #{groupId} "
            + "AND participant_b_type = 'AGENT' AND participant_b_id = #{agentId}")
    int countAgentPeer(@Param("groupId") Long groupId, @Param("agentId") Long agentId);
}
