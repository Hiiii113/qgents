package qg.qgent.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import qg.qgent.common.persistence.typehandler.UuidBinaryTypeHandler;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.view.TeamMemberView;

import java.util.List;
import java.util.UUID;

@Mapper
public interface TeamMemberMapper extends BaseMapper<TeamMemberEntity> {
    @Select("SELECT team_id, user_id, role FROM team_members WHERE team_id = #{teamId} AND user_id = #{userId}")
    @Results(id = "member", value = {@Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "role", property = "role")})
    TeamMemberEntity selectByTeamAndUser(@Param("teamId") UUID teamId, @Param("userId") UUID userId);

    @Select("SELECT team_id, user_id, role FROM team_members WHERE team_id = #{teamId} ORDER BY user_id")
    @ResultMap("member")
    List<TeamMemberEntity> selectByTeamId(@Param("teamId") UUID teamId);

    @Select({"<script>",
            "SELECT tm.team_id, tm.user_id, tm.role, u.display_name, u.email, u.avatar_url",
            "FROM team_members tm INNER JOIN users u ON u.id = tm.user_id",
            "WHERE tm.team_id = #{teamId}",
            "<if test='anchor != null'>AND tm.user_id &gt; #{anchor}</if>",
            "ORDER BY tm.user_id LIMIT #{limit}",
            "</script>"})
    @Results({@Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "role", property = "role"),
            @Result(column = "display_name", property = "displayName"),
            @Result(column = "email", property = "email"),
            @Result(column = "avatar_url", property = "avatarUrl")})
    List<TeamMemberView> selectMemberPage(@Param("teamId") UUID teamId, @Param("anchor") UUID anchor,
                                          @Param("limit") int limit);

    @Insert("INSERT INTO team_members (team_id, user_id, role) VALUES (#{teamId}, #{userId}, #{role})")
    int insert(TeamMemberEntity member);

    @Update("UPDATE team_members SET role = #{role} WHERE team_id = #{teamId} AND user_id = #{userId}")
    int updateRole(@Param("teamId") UUID teamId, @Param("userId") UUID userId, @Param("role") String role);

    @Delete("DELETE FROM team_members WHERE team_id = #{teamId} AND user_id = #{userId}")
    int deleteByTeamAndUser(@Param("teamId") UUID teamId, @Param("userId") UUID userId);

    @Select("SELECT team_id, user_id, role FROM team_members WHERE user_id = #{userId}")
    @ResultMap("member")
    List<TeamMemberEntity> selectByUserId(UUID userId);

}
