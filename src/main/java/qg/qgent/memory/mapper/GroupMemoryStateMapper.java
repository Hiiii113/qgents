package qg.qgent.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import qg.qgent.memory.entity.GroupMemoryStateEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 群记忆提炼状态的数据访问：领取、进度推进和失败重试都在一条语句内完成，避免并发覆盖。
 */
@Mapper
public interface GroupMemoryStateMapper extends BaseMapper<GroupMemoryStateEntity> {

    /**
     * 按键游标分批遍历提炼状态：只包含空闲状态或 RUNNING 已超过领取有效期（上次进程中断）的行，
     * 按 group_id 升序。游标保证每轮扫完所有群，修好的群不会永远占住窗口；
     * 其余触发条件（闲置时长、消息数、重试次数）由服务层判断。
     */
    List<GroupMemoryStateEntity> selectCandidates(
        @Param("staleBefore") LocalDateTime staleBefore,
        @Param("afterGroupId") Long afterGroupId,
        @Param("limit") int limit);

    /**
     * 记录一条新消息：置活跃时间并清零连续失败次数；消息水位只由成功提炼推进。
     */
    int recordActivity(
        @Param("projectId") Long projectId,
        @Param("groupId") Long groupId,
        @Param("at") LocalDateTime at);

    /**
     * 累积一次群记忆纠错；闲置计时重新开始，等待讨论暂停后再提炼。
     */
    int requestCorrection(
        @Param("projectId") Long projectId,
        @Param("groupId") Long groupId,
        @Param("correction") String correction,
        @Param("at") LocalDateTime at);

    /**
     * 领取提炼任务并写入领取标识；返回 0 表示其他任务已领取，本次必须放弃。
     */
    int claim(
        @Param("groupId") Long groupId,
        @Param("now") LocalDateTime now,
        @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * 写结果前确认领取仍然有效；返回 0 表示已被新任务接管，本次结果必须丢弃。
     */
    int renewClaim(
        @Param("groupId") Long groupId,
        @Param("claimToken") LocalDateTime claimToken,
        @Param("now") LocalDateTime now);

    /**
     * 提炼成功后推进水位，并只清空本次实际应用的纠错；水位只增不减，旧任务的完成不会覆盖新进度。
     * 提炼期间新增的纠错文本与本次读到的不同，保留待下一次处理。
     * 群记忆更新后项目记忆发现重新获得重试次数：新内容值得再试一次。
     */
    int finishSuccess(
        @Param("groupId") Long groupId,
        @Param("claimToken") LocalDateTime claimToken,
        @Param("appliedCorrection") String appliedCorrection,
        @Param("processedThrough") Long processedThrough,
        @Param("now") LocalDateTime now);

    /**
     * 提炼失败：保持原水位和待应用纠错不变，记录失败和下次重试时间。
     */
    int recordFailure(
        @Param("groupId") Long groupId,
        @Param("claimToken") LocalDateTime claimToken,
        @Param("error") String error,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
        @Param("now") LocalDateTime now);

    /**
     * 项目记忆发现的到期群：只取项目默认群。受限群内容不进模型、不发建议消息、不写入项目记忆，
     * 因此这里先用 SQL 排除，不依赖提示词或后续校验。连续失败达到上限的群等待群记忆重新提炼后再试。
     */
    List<GroupMemoryStateEntity> selectDiscoveryCandidates(
        @Param("now") LocalDateTime now,
        @Param("maxAttempts") int maxAttempts,
        @Param("limit") int limit);

    /**
     * 领取一轮项目记忆发现并把下次发现时间推到 nextSlot；返回 0 说明其他任务已领取或未到周期。
     */
    int claimDiscovery(
        @Param("groupId") Long groupId,
        @Param("nextSlot") LocalDateTime nextSlot,
        @Param("now") LocalDateTime now);

    /**
     * 发现完成（含“没有建议”）：记录已处理的群记忆版本，清零失败次数；同一版本不再重复发现。
     */
    int finishDiscovery(
        @Param("groupId") Long groupId,
        @Param("version") String version,
        @Param("nextSlot") LocalDateTime nextSlot,
        @Param("now") LocalDateTime now);

    /**
     * 发现失败：不推进 discovered_version，记录失败摘要并安排重试；达到上限后等群记忆更新。
     */
    int recordDiscoveryFailure(
        @Param("groupId") Long groupId,
        @Param("error") String error,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
        @Param("now") LocalDateTime now);
}
