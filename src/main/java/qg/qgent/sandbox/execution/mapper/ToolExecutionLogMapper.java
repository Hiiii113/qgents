package qg.qgent.sandbox.execution.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.sandbox.execution.entity.ToolExecutionLogEntity;

/**
 * 工具执行日志的数据访问接口。
 */
@Mapper
public interface ToolExecutionLogMapper {
    int insert(@Param("entity") ToolExecutionLogEntity entity);

    long selectMaxSequence(@Param("executionId") Long executionId);
}
