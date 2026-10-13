package qg.qgent.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import qg.qgent.common.persistence.typehandler.UuidBinaryTypeHandler;
import qg.qgent.user.entity.UserEntity;

import java.util.UUID;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    @Update("UPDATE users SET last_team_id = #{teamId} WHERE id = #{userId}")
    int updateLastTeam(@Param("userId") UUID userId, @Param("teamId") UUID teamId);

    /** 锁定用户行，用作该用户通知序号分配的跨实例串行化锚点。 */
    @Select("SELECT id, email, display_name, avatar_url, avatar_object_key, last_team_id, password_hash, "
            + "status, created_at, updated_at "
            + "FROM users WHERE id = #{userId} FOR UPDATE")
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "password_hash", property = "passwordHash"),
            @Result(column = "display_name", property = "displayName"),
            @Result(column = "avatar_url", property = "avatarUrl"),
            @Result(column = "avatar_object_key", property = "avatarObjectKey"),
            @Result(column = "last_team_id", property = "lastTeamId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "status", property = "status"),
            @Result(column = "created_at", property = "createdAt")
    })
    UserEntity selectByIdForUpdate(@Param("userId") UUID userId);
}
