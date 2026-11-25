package qg.qgent.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import qg.qgent.workspace.entity.WorkspaceEntity;

/**
 * 工作区记录的数据库操作。
 */
@Mapper
public interface WorkspaceMapper extends BaseMapper<WorkspaceEntity> {
}
