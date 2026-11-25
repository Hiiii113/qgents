package qg.qgent.harness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.harness.entity.AgentRunToolEntity;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface AgentRunToolMapper extends BaseMapper<AgentRunToolEntity> {

    /**
     * 工具结束时结算状态与结果；开始行已由 {@code insert} 写入。
     */
    void finish(
        @Param("runId") Long runId,
        @Param("toolCallId") String toolCallId,
        @Param("state") String state,
        @Param("output") String output,
        @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 一次取回多个运行的工具明细，按写入顺序即工具开始顺序返回。
     */
    List<AgentRunToolEntity> byRunIds(@Param("runIds") Collection<Long> runIds);
}
