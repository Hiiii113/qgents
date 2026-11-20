package qg.qgent.harness.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.event.DirectMessageSentEvent;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.MessageService;
import qg.qgent.harness.runtime.AgentChatService;

/**
 * 私聊消息提交后启动对端 Agent 的回复；幂等重试返回原消息时不会发出本事件。
 */
@Component
public class DirectMessageSentListener {
    private static final Logger log = LoggerFactory.getLogger(DirectMessageSentListener.class);

    private final MessageMapper messages;
    private final AgentChatService agentChatService;
    private final MessageService messageService;

    public DirectMessageSentListener(
        MessageMapper messages,
        AgentChatService agentChatService,
        MessageService messageService) {
        this.messages = messages;
        this.agentChatService = agentChatService;
        this.messageService = messageService;
    }

    /**
     * 消息提交后异步执行，不占用发送消息的请求线程；失败只记日志，用户在私聊里看得到没有回复。
     */
    @Async("agentExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDirectMessageSent(DirectMessageSentEvent event) {
        try {
            MessageEntity message = messages.selectById(event.messageId());
            if (message == null) {
                return;
            }
            agentChatService.replyToDirectMessage(
                event, messageService.agentInstruction(message));
        } catch (RuntimeException e) {
            log.warn(
                "direct message agent run skipped, conversationId={}, agentId={}: {}",
                event.conversationId(),
                event.agentId(),
                e.getMessage());
        }
    }
}
