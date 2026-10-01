package qg.qgent.chat.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import qg.qgent.agent.runtime.AgentChatService;
import qg.qgent.chat.dto.Mention;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.service.MessageService;

import java.util.List;
import java.util.UUID;

/**
 * 消息提交后通知被 @ 的用户，并启动被 @ 的 Agent。普通聊天不会调用模型。
 */
@Component
public class MessageSentListener {
    private static final Logger log = LoggerFactory.getLogger(MessageSentListener.class);

    private final MessageMapper messageMapper;
    private final ChatGroupMapper groupMapper;
    private final AgentChatService agentChatService;
    private final MessageService messageService;

    public MessageSentListener(MessageMapper messageMapper, ChatGroupMapper groupMapper,
                               AgentChatService agentChatService, MessageService messageService) {
        this.messageMapper = messageMapper;
        this.groupMapper = groupMapper;
        this.agentChatService = agentChatService;
        this.messageService = messageService;
    }

    /**
     * 消息提交后异步执行，不占用发送消息的请求线程。
     */
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
            boolean hasAgent = mentions.stream().anyMatch(m -> "AGENT".equals(m.getType()));
            boolean hasUser = mentions.stream().anyMatch(m -> "USER".equals(m.getType()));
            if (hasUser) {
                ChatGroupEntity group = groupMapper.selectById(event.groupId());
                messageService.notifyMentionedUsersAfterCommit(event,
                        group == null ? "群聊" : group.getName(),
                        messageService.extractTextPreview(message));
            }
            if (hasAgent && message != null) {
                for (UUID agentId : mentions.stream().filter(m -> "AGENT".equals(m.getType()))
                        .map(Mention::getId).distinct().toList()) {
                    agentChatService.replyToMention(event, agentId, messageService.agentInstruction(message));
                }
            }
        } catch (RuntimeException e) {
            log.warn("message sent post-processing skipped, messageId={}: {}", messageId, e.getMessage());
        }
    }
}
