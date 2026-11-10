package qg.qgent.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 群记忆后台提炼的调度与进度状态，对应表 group_memory_state（一 group 一行）。 */
@Data
@TableName("group_memory_state")
public class GroupMemoryStateEntity {

    // 项目群聊 ID，与群一一对应。
    @TableId(type = IdType.INPUT)
    private Long groupId;

    // 所属项目 ID。
    private Long projectId;

    // 已提炼到的群内消息序号；只有写入成功后才推进。
    private Long lastProcessedSequence;

    // 最近一条消息时间（UTC），用于闲置时长判定。
    private LocalDateTime lastMessageAt;

    // IDLE/RUNNING：扫描领取后置 RUNNING，用来互斥并发提炼。
    private String status;

    // 连续失败次数；新消息清零，达到上限后等待新消息再试。
    private Integer attemptCount;

    // 下次允许重试的时间（UTC）。
    private LocalDateTime nextAttemptAt;

    // 用户通过对话提出的待应用纠错内容。
    private String correction;

    // 最近一次失败摘要。
    private String lastError;

    private LocalDateTime updatedAt;
}
