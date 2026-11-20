package qg.qgent.chat.event;

/**
 * 私聊里用户发出的新消息已提交，供触发对端 Agent 的回复；接话的人自己决定是否运行，
 * 幂等重试返回原消息时不会重复发布本事件。
 */
public record DirectMessageSentEvent(
    Long actor, Long projectId, Long conversationId, Long messageId, Long agentId) {
}
