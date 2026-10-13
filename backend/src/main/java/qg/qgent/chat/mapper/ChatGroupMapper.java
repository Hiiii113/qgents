package qg.qgent.chat.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import qg.qgent.chat.entity.ChatGroupEntity;
import java.util.List;
import java.util.UUID;
@Mapper
public interface ChatGroupMapper extends BaseMapper<ChatGroupEntity> {
    @Select("SELECT user_id FROM group_users WHERE group_id = #{groupId}")
    List<UUID> selectUserIds(UUID groupId);
    @Select("select * from chat_groups where project_id=#{projectId} ORDER BY default_group DESC, created_at ASC")
    List<ChatGroupEntity> listByProject(UUID projectId);
}
