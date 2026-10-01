package qg.qgent.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import qg.qgent.task.entity.TaskEntity;

import java.util.UUID;

@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {
    @Select("SELECT MAX(CAST(SUBSTRING_INDEX(display_code, '-', -1) AS UNSIGNED)) FROM tasks WHERE project_id = #{projectId}")
    Long selectMaxDisplayCodeSeq(@Param("projectId") UUID projectId);

    @Select("SELECT COUNT(*) FROM tasks t WHERE t.status IN ('PENDING','RUNNING','CANCELLING') "
            + "AND EXISTS (SELECT 1 FROM workspace_repositories wr WHERE wr.workspace_id = t.workspace_id "
            + "AND wr.project_repository_id = #{projectRepositoryId})")
    int countActiveTasksUsingRepository(@Param("projectRepositoryId") UUID projectRepositoryId);
}
