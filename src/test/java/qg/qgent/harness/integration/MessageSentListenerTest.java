package qg.qgent.harness.integration;

import qg.qgent.support.TestIds;

import qg.qgent.chat.event.MessageSentEvent;

import org.junit.jupiter.api.Test;

import qg.qgent.harness.runtime.AgentChatService;
import qg.qgent.chat.dto.Mention;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.MessageService;

import java.util.List;

import static org.mockito.Mockito.*;

class MessageSentListenerTest {
    private final MessageMapper messages = mock(MessageMapper.class);
    private final AgentChatService agents = mock(AgentChatService.class);
    private final MessageService messageService = mock(MessageService.class);
    private final MessageSentListener listener = new MessageSentListener(messages, agents, messageService);

    @Test
    void ordinaryMessagesDoNotStartAgents() {
        listener.onMessageSent(event(List.of()));
        verifyNoInteractions(agents, messages);
    }

    @Test
    void userMentionsDoNotStartAgents() {
        Mention mention = new Mention();
        mention.setType("USER");
        mention.setId(TestIds.next());
        listener.onMessageSent(event(List.of(mention)));
        verifyNoInteractions(agents);
    }

    @Test
    void qgentMentionStartsOneRunForDuplicateMentions() {
        Mention mention = new Mention();
        mention.setType("AGENT");
        mention.setId(qg.qgent.chat.QgentIdentity.ID);
        MessageSentEvent event = event(List.of(mention, mention));
        MessageEntity message = new MessageEntity();
        message.setContent("{\"text\":\"你好\"}");
        when(messages.selectById(event.messageId())).thenReturn(message);
        when(messageService.agentInstruction(message)).thenReturn("你好");
        listener.onMessageSent(event);
        verify(agents).replyToMention(event, "你好");
        verifyNoMoreInteractions(agents);
    }

    @Test
    void arbitraryAgentIdsCannotTriggerQgent() {
        Mention mention = new Mention();
        mention.setType("AGENT");
        mention.setId(TestIds.next());
        MessageSentEvent event = event(List.of(mention));
        MessageEntity message = new MessageEntity();
        message.setContent("{\"text\":\"你好\"}");
        when(messages.selectById(event.messageId())).thenReturn(message);
        when(messageService.agentInstruction(message)).thenReturn("你好");
        listener.onMessageSent(event);
        verifyNoInteractions(agents);
    }

    private MessageSentEvent event(List<Mention> mentions) {
        return new MessageSentEvent(TestIds.next(), TestIds.next(), TestIds.next(), TestIds.next(), mentions);
    }
}
