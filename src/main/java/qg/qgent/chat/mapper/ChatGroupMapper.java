package qg.qgent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.chat.entity.ChatGroupEntity;

import java.util.List;

@Mapper
public interface ChatGroupMapper extends BaseMapper<ChatGroupEntity> {
    List<Long> selectUserIds(@Param("groupId") Long groupId);

    List<ChatGroupEntity> listByProject(@Param("projectId") Long projectId);
}
