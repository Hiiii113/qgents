package qg.qgent.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import qg.qgent.memory.entity.GroupMemoryStateEntity;

import java.time.LocalDateTime;
import java.util.List;

/** 群记忆提炼状态的数据访问：领取、进度推进和失败重试都在一条语句内完成，避免并发覆盖。 */
@Mapper
public interface GroupMemoryStateMapper extends BaseMapper<GroupMemoryStateEntity> {

    /**
     * 待扫描的群状态：空闲状态，或 RUNNING 已超过领取有效期（上次进程中断）。
     * 其余触发条件（闲置时长、消息数、重试次数）由服务层判断。
     */
    @Select("SELECT group_id, project_id, last_processed_sequence, last_message_at, status, attempt_count, "
            + "next_attempt_at, correction, last_error, updated_at FROM group_memory_state "
            + "WHERE status = 'IDLE' OR updated_at < #{staleBefore}")
    List<GroupMemoryStateEntity> selectCandidates(@Param("staleBefore") LocalDateTime staleBefore);

    /** 记录一条新消息：置活跃时间并清零连续失败次数；msg seq 水位由成功提炼推进。 */
    @Insert("INSERT INTO group_memory_state (group_id, project_id, last_message_at, last_processed_sequence, "
            + "status, attempt_count, updated_at) VALUES (#{groupId}, #{projectId}, #{at}, 0, 'IDLE', 0, #{at}) "
            + "ON DUPLICATE KEY UPDATE last_message_at = #{at}, attempt_count = 0, next_attempt_at = NULL, "
            + "updated_at = #{at}")
    int recordActivity(@Param("projectId") Long projectId, @Param("groupId") Long groupId,
                       @Param("at") LocalDateTime at);

    /** 记录一次群记忆纠错请求；闲置计时从此重新开始，等待讨论暂停后再提炼。 */
    @Insert("INSERT INTO group_memory_state (group_id, project_id, correction, last_message_at, "
            + "last_processed_sequence, status, attempt_count, updated_at) "
            + "VALUES (#{groupId}, #{projectId}, #{correction}, #{at}, 0, 'IDLE', 0, #{at}) "
            + "ON DUPLICATE KEY UPDATE correction = #{correction}, last_message_at = #{at}, attempt_count = 0, "
            + "next_attempt_at = NULL, updated_at = #{at}")
    int requestCorrection(@Param("projectId") Long projectId, @Param("groupId") Long groupId,
                          @Param("correction") String correction, @Param("at") LocalDateTime at);

    /** 领取提炼任务；返回 0 表示其他任务已领取，本次必须放弃。 */
    @Update("UPDATE group_memory_state SET status = 'RUNNING', updated_at = #{now} "
            + "WHERE group_id = #{groupId} AND (status = 'IDLE' OR updated_at < #{staleBefore})")
    int claim(@Param("groupId") Long groupId, @Param("now") LocalDateTime now,
              @Param("staleBefore") LocalDateTime staleBefore);

    /** 提炼成功后推进水位并清空纠错；前缀写入只在这里发生。 */
    @Update("UPDATE group_memory_state SET status = 'IDLE', last_processed_sequence = #{processedThrough}, "
            + "attempt_count = 0, next_attempt_at = NULL, correction = NULL, last_error = NULL, updated_at = #{now} "
            + "WHERE group_id = #{groupId}")
    int finishSuccess(@Param("groupId") Long groupId, @Param("processedThrough") Long processedThrough,
                      @Param("now") LocalDateTime now);

    /** 提炼失败：保持原水位不变，记录失败和下次重试时间。 */
    @Update("UPDATE group_memory_state SET status = 'IDLE', attempt_count = attempt_count + 1, "
            + "next_attempt_at = #{nextAttemptAt}, last_error = #{error}, updated_at = #{now} "
            + "WHERE group_id = #{groupId}")
    int recordFailure(@Param("groupId") Long groupId, @Param("error") String error,
                      @Param("nextAttemptAt") LocalDateTime nextAttemptAt, @Param("now") LocalDateTime now);
}
