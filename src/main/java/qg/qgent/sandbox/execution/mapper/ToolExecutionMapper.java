package qg.qgent.sandbox.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.sandbox.execution.entity.ExecutionStatus;
import qg.qgent.sandbox.execution.entity.ToolExecutionEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具执行记录的数据访问接口。
 */
@Mapper
public interface ToolExecutionMapper extends BaseMapper<ToolExecutionEntity> {
    int markInterrupted(
        @Param("instanceId") String instanceId, @Param("finishedAt") LocalDateTime finishedAt);

    int markRunning(
        @Param("id") Long id,
        @Param("instanceId") String instanceId,
        @Param("startedAt") LocalDateTime startedAt);

    int markCancelled(
        @Param("id") Long id,
        @Param("instanceId") String instanceId,
        @Param("finishedAt") LocalDateTime finishedAt);

    int finishIfRunning(
        @Param("id") Long id,
        @Param("instanceId") String instanceId,
        @Param("status") ExecutionStatus status,
        @Param("exitCode") Integer exitCode,
        @Param("resultJson") String resultJson,
        @Param("failureCode") String failureCode,
        @Param("failureReason") String failureReason,
        @Param("finishedAt") LocalDateTime finishedAt);

    int rejectQueued(
        @Param("id") Long id,
        @Param("instanceId") String instanceId,
        @Param("failureCode") String failureCode,
        @Param("failureReason") String failureReason,
        @Param("finishedAt") LocalDateTime finishedAt);

    List<Long> selectActiveIdsBySandbox(
        @Param("sandboxId") String sandboxId, @Param("instanceId") String instanceId);

    List<Long> selectActiveIdsByAgentRun(
        @Param("agentRunId") Long agentRunId, @Param("instanceId") String instanceId);
}
