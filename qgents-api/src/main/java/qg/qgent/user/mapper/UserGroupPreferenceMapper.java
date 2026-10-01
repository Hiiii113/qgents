package qg.qgent.user.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import qg.qgent.common.persistence.typehandler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

/** 用户 × 群置顶偏好数据访问（表 user_group_preference）。 */
@Mapper
public interface UserGroupPreferenceMapper {

    /** 设置 / 取消某用户对某群的置顶状态。 */
    @Insert("INSERT INTO user_group_preference (user_id, group_id, pinned) "
            + "VALUES (#{userId}, #{groupId}, #{pinned}) "
            + "ON DUPLICATE KEY UPDATE pinned = VALUES(pinned)")
    void upsertPin(@Param("userId") UUID userId, @Param("groupId") UUID groupId, @Param("pinned") boolean pinned);

    /** 批量取某用户在一组群中的置顶群 ID（仅 pinned = true 者），一次 IN 查询避免 N+1。 */
    @Select({"<script>",
            "SELECT group_id FROM user_group_preference WHERE user_id = #{userId} AND pinned = 1 ",
            "AND group_id IN "
            + "(<foreach collection='groupIds' item='gid' separator=','>#{gid}</foreach>)",
            "</script>"})
    @Results({
            @Result(column = "group_id", property = "groupId", typeHandler = UuidBinaryTypeHandler.class)
    })
    List<UUID> selectPinnedGroupIds(@Param("userId") UUID userId, @Param("groupIds") List<UUID> groupIds);
}
