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

}
