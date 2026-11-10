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
     * 待扫描的群状态：空闲状态，或 RUNNING 已超过领取有效期（上次进程中断）；最久未活动的在前。
     * 其余触发条件（闲置时长、消息数、重试次数）由服务层判断。
     */
    @Select("SELECT group_id, project_id, last_processed_sequence, last_message_at, status, claim_token, "
            + "attempt_count, next_attempt_at, correction, last_error, updated_at FROM group_memory_state "
            + "WHERE status = 'IDLE' OR updated_at < #{staleBefore} "
            + "ORDER BY last_message_at LIMIT #{limit}")
    List<GroupMemoryStateEntity> selectCandidates(@Param("staleBefore") LocalDateTime staleBefore,
                                                  @Param("limit") int limit);

    /** 记录一条新消息：置活跃时间并清零连续失败次数；消息水位只由成功提炼推进。 */
    @Insert("INSERT INTO group_memory_state (group_id, project_id, last_message_at, last_processed_sequence, "
            + "status, attempt_count, updated_at) VALUES (#{groupId}, #{projectId}, #{at}, 0, 'IDLE', 0, #{at}) "
            + "ON DUPLICATE KEY UPDATE last_message_at = #{at}, attempt_count = 0, next_attempt_at = NULL, "
            + "updated_at = #{at}")
    int recordActivity(@Param("projectId") Long projectId, @Param("groupId") Long groupId,
                       @Param("at") LocalDateTime at);

    /** 累积一次群记忆纠错；闲置计时重新开始，等待讨论暂停后再提炼。 */
    @Insert("INSERT INTO group_memory_state (group_id, project_id, correction, last_message_at, "
            + "last_processed_sequence, status, attempt_count, updated_at) "
            + "VALUES (#{groupId}, #{projectId}, #{correction}, #{at}, 0, 'IDLE', 0, #{at}) "
            + "ON DUPLICATE KEY UPDATE correction = CASE WHEN correction IS NULL OR correction = '' "
            + "THEN #{correction} ELSE CONCAT(correction, CHAR(10), #{correction}) END, "
            + "last_message_at = #{at}, attempt_count = 0, next_attempt_at = NULL, updated_at = #{at}")
    int requestCorrection(@Param("projectId") Long projectId, @Param("groupId") Long groupId,
                          @Param("correction") String correction, @Param("at") LocalDateTime at);

    /** 领取提炼任务并写入领取标识；返回 0 表示其他任务已领取，本次必须放弃。 */
    @Update("UPDATE group_memory_state SET status = 'RUNNING', claim_token = #{now}, updated_at = #{now} "
            + "WHERE group_id = #{groupId} AND (status = 'IDLE' OR updated_at < #{staleBefore})")
    int claim(@Param("groupId") Long groupId, @Param("now") LocalDateTime now,
              @Param("staleBefore") LocalDateTime staleBefore);

    /** 写结果前确认领取仍然有效；返回 0 表示已被新任务接管，本次结果必须丢弃。 */
    @Update("UPDATE group_memory_state SET updated_at = #{now} "
            + "WHERE group_id = #{groupId} AND status = 'RUNNING' AND claim_token = #{claimToken}")
    int renewClaim(@Param("groupId") Long groupId, @Param("claimToken") LocalDateTime claimToken,
                   @Param("now") LocalDateTime now);

    /** 提炼成功后推进水位并清空已应用的纠错；水位只增不减，旧任务的完成不会覆盖新进度。 */
    @Update("UPDATE group_memory_state SET status = 'IDLE', claim_token = NULL, "
            + "last_processed_sequence = GREATEST(last_processed_sequence, #{processedThrough}), "
            + "attempt_count = 0, next_attempt_at = NULL, correction = NULL, last_error = NULL, updated_at = #{now} "
            + "WHERE group_id = #{groupId} AND status = 'RUNNING' AND claim_token = #{claimToken}")
    int finishSuccess(@Param("groupId") Long groupId, @Param("claimToken") LocalDateTime claimToken,
                      @Param("processedThrough") Long processedThrough, @Param("now") LocalDateTime now);

    /** 提炼失败：保持原水位和待应用纠错不变，记录失败和下次重试时间。 */
    @Update("UPDATE group_memory_state SET status = 'IDLE', claim_token = NULL, "
            + "attempt_count = attempt_count + 1, next_attempt_at = #{nextAttemptAt}, last_error = #{error}, "
            + "updated_at = #{now} "
            + "WHERE group_id = #{groupId} AND status = 'RUNNING' AND claim_token = #{claimToken}")
    int recordFailure(@Param("groupId") Long groupId, @Param("claimToken") LocalDateTime claimToken,
                      @Param("error") String error, @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                      @Param("now") LocalDateTime now);
}
