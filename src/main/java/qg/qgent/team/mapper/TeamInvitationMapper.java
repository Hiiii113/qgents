package qg.qgent.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.team.entity.TeamInvitationEntity;

import java.util.List;

@Mapper
public interface TeamInvitationMapper extends BaseMapper<TeamInvitationEntity> {
    List<TeamInvitationEntity> selectInvitationPage(
        @Param("teamId") Long teamId, @Param("anchor") Long anchor, @Param("limit") int limit);

    /**
     * 按被邀请邮箱分页查询待处理（PENDING）邀请，按 id 升序，用于收件人视角列表。
     */
    List<TeamInvitationEntity> selectPendingByEmail(
        @Param("emailNormalized") String emailNormalized,
        @Param("anchor") Long anchor,
        @Param("limit") int limit);
}
