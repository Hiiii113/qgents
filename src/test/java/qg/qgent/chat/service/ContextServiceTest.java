package qg.qgent.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.mapper.MemoryMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.skill.mapper.SkillMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextServiceTest {
    private final MessageMapper messages = mock(MessageMapper.class);
    private final GroupService groups = mock(GroupService.class);
    private final ContextService service = new ContextService(mock(ChatGroupMapper.class), messages,
            mock(SkillMapper.class), mock(MemoryMapper.class), mock(ProjectAccessService.class), groups, new ObjectMapper());

    @Test
    void readingMessagePreservesFullBodyAndReplyRelationship() {
        UUID actor = UUID.randomUUID(), project = UUID.randomUUID(), group = UUID.randomUUID();
        UUID id = UUID.randomUUID(), original = UUID.randomUUID();
        MessageEntity message = new MessageEntity();
        message.setId(id);
        message.setGroupId(group);
        message.setReplyToMessageId(original);
        message.setMessageType("TEXT");
        message.setContent("{\"text\":\"" + "需求".repeat(500) + "\"}");
        when(messages.selectById(id)).thenReturn(message);
        var result = service.readChatMessage(actor, project, group, id);
        assertThat(result.getId()).isEqualTo(id.toString());
        assertThat(result.getReplyToId()).isEqualTo(original.toString());
        assertThat(result.getText()).isEqualTo("需求".repeat(500));
        verify(groups).requireGroupMember(project, group, actor);
    }

    @Test
    void messageFromAnotherGroupIsNotReturned() {
        UUID id = UUID.randomUUID();
        MessageEntity message = new MessageEntity();
        message.setGroupId(UUID.randomUUID());
        when(messages.selectById(id)).thenReturn(message);
        assertThatThrownBy(() -> service.readChatMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), id))
                .isInstanceOf(ApiException.class);
    }
}
