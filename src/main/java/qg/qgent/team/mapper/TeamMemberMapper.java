package qg.qgent.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.view.TeamMemberCountRow;
import qg.qgent.team.mapper.view.TeamMemberView;

import java.util.Collection;
import java.util.List;

@Mapper
public interface TeamMemberMapper extends BaseMapper<TeamMemberEntity> {
    default TeamMemberEntity selectByTeamAndUser(Long teamId, Long userId) {
        return selectOne(Wrappers.<TeamMemberEntity>lambdaQuery()
            .eq(TeamMemberEntity::getTeamId, teamId)
            .eq(TeamMemberEntity::getUserId, userId));
    }

    default List<TeamMemberEntity> selectByTeamId(Long teamId) {
        return selectList(Wrappers.<TeamMemberEntity>lambdaQuery()
            .eq(TeamMemberEntity::getTeamId, teamId)
            .orderByAsc(TeamMemberEntity::getUserId));
    }

    List<TeamMemberView> selectMemberPage(
        @Param("teamId") Long teamId, @Param("anchor") Long anchor, @Param("limit") int limit);

    default int updateRole(Long teamId, Long userId, String role) {
        return update(null, Wrappers.<TeamMemberEntity>lambdaUpdate()
            .set(TeamMemberEntity::getRole, role)
            .eq(TeamMemberEntity::getTeamId, teamId)
            .eq(TeamMemberEntity::getUserId, userId));
    }

    default int deleteByTeamAndUser(Long teamId, Long userId) {
        return delete(Wrappers.<TeamMemberEntity>lambdaQuery()
            .eq(TeamMemberEntity::getTeamId, teamId)
            .eq(TeamMemberEntity::getUserId, userId));
    }

    default List<TeamMemberEntity> selectByUserId(Long userId) {
        return selectList(Wrappers.<TeamMemberEntity>lambdaQuery()
            .eq(TeamMemberEntity::getUserId, userId)
            .orderByAsc(TeamMemberEntity::getTeamId));
    }

    /**
     * 一次取回多个团队的成员数，避免逐团队统计。
     */
    List<TeamMemberCountRow> countByTeamIds(@Param("teamIds") Collection<Long> teamIds);
}
