package qg.qgent.chat.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import qg.qgent.chat.entity.ChatGroupEntity;
import java.util.List;
import java.util.UUID;
@Mapper
public interface ChatGroupMapper extends BaseMapper<ChatGroupEntity> {
    @Select("select * from chat_groups where project_id=#{projectId}")
    List<ChatGroupEntity> listByProject(UUID projectId);
    @Select({"<script>", "SELECT * FROM chat_groups WHERE project_id IN ",
            "(<foreach collection='projectIds' item='pid' separator=','>#{pid}</foreach>)",
            "ORDER BY coalesce(last_message_at, created_at) DESC", "</script>"})
    List<ChatGroupEntity> selectByProjectIds(@Param("projectIds") List<UUID> projectIds);
}
