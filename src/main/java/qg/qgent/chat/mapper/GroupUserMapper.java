package qg.qgent.chat.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.chat.mapper.view.GroupMemberRow;

import java.util.List;

/**
 * 新建群的用户成员；默认群仍直接使用项目成员。
 */
@Mapper
public interface GroupUserMapper {
    int insertUser(@Param("groupId") Long groupId, @Param("userId") Long userId);

    List<GroupMemberRow> selectMembers(@Param("groupId") Long groupId);

    List<Long> selectUserIds(@Param("groupId") Long groupId);
}
