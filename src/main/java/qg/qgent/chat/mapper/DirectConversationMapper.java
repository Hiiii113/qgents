package qg.qgent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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
    List<DirectConversationEntity> listForUser(
        @Param("projectId") Long projectId, @Param("userId") Long userId);

    /**
     * 该 Agent 是否为这条私聊的对端；Agent 只能回复自己作为参与者的会话。
     */
    int countAgentPeer(@Param("groupId") Long groupId, @Param("agentId") Long agentId);
}
