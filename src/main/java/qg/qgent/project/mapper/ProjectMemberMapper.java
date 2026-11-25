package qg.qgent.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.chat.mapper.view.GroupMemberRow;
import qg.qgent.project.entity.ProjectMemberEntity;

import java.util.List;

@Mapper
public interface ProjectMemberMapper extends BaseMapper<ProjectMemberEntity> {
    int deleteByTeamAndUser(@Param("teamId") Long teamId, @Param("userId") Long userId);

    default ProjectMemberEntity selectByProjectAndUser(Long projectId, Long userId) {
        return selectOne(Wrappers.<ProjectMemberEntity>lambdaQuery()
            .eq(ProjectMemberEntity::getProjectId, projectId)
            .eq(ProjectMemberEntity::getUserId, userId));
    }

    List<ProjectMemberEntity> selectMemberPage(
        @Param("projectId") Long projectId,
        @Param("anchor") Long anchor,
        @Param("limit") int limit);

    default int updateRole(Long projectId, Long userId, String role) {
        return update(null, Wrappers.<ProjectMemberEntity>lambdaUpdate()
            .set(ProjectMemberEntity::getRole, role)
            .eq(ProjectMemberEntity::getProjectId, projectId)
            .eq(ProjectMemberEntity::getUserId, userId));
    }

    default int deleteByProjectAndUser(Long projectId, Long userId) {
        return delete(Wrappers.<ProjectMemberEntity>lambdaQuery()
            .eq(ProjectMemberEntity::getProjectId, projectId)
            .eq(ProjectMemberEntity::getUserId, userId));
    }

    /**
     * 查询项目成员列表（含昵称、头像、邮箱），按加入时间排序（默认群聊成员来自项目成员）。
     */
    List<GroupMemberRow> selectMembers(@Param("projectId") Long projectId);

    /**
     * 统计项目成员数（用作群详情 memberCount）。
     */
    default Long countMembers(Long projectId) {
        return selectCount(Wrappers.<ProjectMemberEntity>lambdaQuery()
            .eq(ProjectMemberEntity::getProjectId, projectId));
    }
}
