package qg.qgent.service;

import org.junit.jupiter.api.Test;

import qg.qgent.runtime.AgentChatService;
import qg.qgent.dto.Mention;
import qg.qgent.entity.MessageEntity;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.service.MessageService;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

class MessageSentListenerTest {
    private final MessageMapper messages = mock(MessageMapper.class);
    private final AgentChatService agents = mock(AgentChatService.class);
    private final MessageService messageService = mock(MessageService.class);
    private final MessageSentListener listener = new MessageSentListener(messages,
            mock(RequirementGroupMapper.class), agents, messageService);

    @Test
    void ordinaryMessagesDoNotStartAgents() {
        listener.onMessageSent(event(List.of()));
        verifyNoInteractions(agents, messages);
    }

    @Test
    void userMentionsDoNotStartAgents() {
        Mention mention = new Mention();
        mention.setType("USER");
        mention.setId(UUID.randomUUID());
        listener.onMessageSent(event(List.of(mention)));
        verifyNoInteractions(agents);
    }

    @Test
    void agentMentionStartsExactlyTheMentionedAgent() {
        Mention mention = new Mention();
        mention.setType("AGENT");
        mention.setId(UUID.randomUUID());
        MessageSentEvent event = event(List.of(mention, mention));
        MessageEntity message = new MessageEntity();
        message.setContent("{\"text\":\"你好\"}");
        when(messages.selectById(event.messageId())).thenReturn(message);
        listener.onMessageSent(event);
        verify(agents).replyToMention(event, mention.getId(), message.getContent());
        verifyNoMoreInteractions(agents);
    }

    private MessageSentEvent event(List<Mention> mentions) {
        return new MessageSentEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), mentions);
    }
}
