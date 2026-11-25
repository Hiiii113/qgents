package qg.qgent.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.mapper.view.TeamMembershipView;

import java.util.List;

@Mapper
public interface TeamMapper extends BaseMapper<TeamEntity> {
    TeamEntity selectByIdForUpdate(@Param("teamId") Long teamId);

    List<TeamMembershipView> selectMembershipPage(
        @Param("userId") Long userId, @Param("anchor") Long anchor, @Param("limit") int limit);
}
