package qg.qgent.chat.mapper.view;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 群最新消息批量查询结果行：{@code MessageMapper.selectLatestByGroupIds} 的映射结果。
 * <p>
 * 由 Service 转换为 {@link GroupLatestMessage} 供群列表展示；Agent 消息显示固定名称 Qgent，
 * 用户消息使用用户显示名称，SYSTEM 消息为空。
 */
@Data
public class GroupLatestMessageRow {

    // 所属项目群聊 ID。
    private UUID groupId;

    // 最新消息发送者名称（Agent 固定显示 Qgent，用户使用 displayName）；SYSTEM 消息为空。
    private String senderName;

    // 最新消息文本摘要（content 的 $.text）；非文本消息为空。
    private String text;

    // 最新消息类型，供前端对非文本摘要降级展示。
    private String messageType;

    // 最新消息发送时间（UTC）。
    private LocalDateTime createdAt;
}
