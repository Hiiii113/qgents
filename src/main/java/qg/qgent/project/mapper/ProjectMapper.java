package qg.qgent.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.mapper.view.ProjectMembershipView;

import java.util.List;

@Mapper
public interface ProjectMapper extends BaseMapper<ProjectEntity> {
    ProjectEntity selectByIdForUpdate(@Param("projectId") Long projectId);

    List<ProjectMembershipView> selectAccessiblePage(
        @Param("teamId") Long teamId,
        @Param("userId") Long userId,
        @Param("teamOwner") boolean teamOwner,
        @Param("anchor") Long anchor,
        @Param("limit") int limit);

    List<ProjectMembershipView> selectAccessibleByUser(@Param("userId") Long userId);
}
