package qg.qgent.harness.integration;

import qg.qgent.chat.event.MessageSentEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import qg.qgent.harness.runtime.AgentChatService;
import qg.qgent.chat.dto.Mention;
import qg.qgent.chat.QgentIdentity;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.MessageService;

import java.util.List;
import java.util.UUID;

/** 将已提交的 Qgent 提及适配为 Harness 运行；普通聊天和用户提及不调用模型。 */
@Component
public class MessageSentListener {
    private static final Logger log = LoggerFactory.getLogger(MessageSentListener.class);

    private final MessageMapper messageMapper;
    private final AgentChatService agentChatService;
    private final MessageService messageService;

    public MessageSentListener(MessageMapper messageMapper, AgentChatService agentChatService,
                               MessageService messageService) {
        this.messageMapper = messageMapper;
        this.agentChatService = agentChatService;
        this.messageService = messageService;
    }

    /** 消息提交后异步执行，不占用发送消息的请求线程。 */
    @Async("agentExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageSent(MessageSentEvent event) {
        UUID messageId = event.messageId();
        List<Mention> mentions = event.mentions();
        if (mentions == null || mentions.isEmpty()) {
            return;
        }
        try {
            MessageEntity message = messageMapper.selectById(messageId);
            boolean hasAgent = mentions.stream().anyMatch(m -> "AGENT".equals(m.getType()) && QgentIdentity.ID.equals(m.getId()));
            if (hasAgent && message != null) {
                agentChatService.replyToMention(event, messageService.agentInstruction(message));
            }
        } catch (RuntimeException e) {
            log.warn("message sent post-processing skipped, messageId={}: {}", messageId, e.getMessage());
        }
    }
}
