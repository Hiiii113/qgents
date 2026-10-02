package qg.qgent.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import qg.qgent.agent.entity.AgentRunEntity;
import qg.qgent.agent.mapper.AgentRunMapper;
import qg.qgent.chat.dto.MessageResponse;
import qg.qgent.chat.service.MessageService;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.service.ProjectAccessService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRunServiceTest {
    private final UUID actor = UUID.randomUUID(), projectId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID(), messageId = UUID.randomUUID();
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final MessageService messages = mock(MessageService.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final AgentRunService service = new AgentRunService(runs, messages, access);

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentRunEntity.class);
    }

    @Test
    void senderCanCancelOnlyRunningRootsFromTheSelectedMessage() {
        messageFrom(actor);
        when(runs.update(isNull(), any())).thenReturn(2);
        assertThat(service.cancel(actor, projectId, groupId, messageId)).isEqualTo(2);
        ArgumentCaptor<Wrapper<AgentRunEntity>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(runs).update(isNull(), wrapper.capture());
        String sql = wrapper.getValue().getSqlSegment();
        assertThat(sql).contains("project_id", "group_id", "trigger_message_id", "parent_run_id IS NULL", "status");
        verifyNoInteractions(access);
    }

    @Test
    void cancellingAnotherUsersExecutionRequiresProjectAdmin() {
        messageFrom(UUID.randomUUID());
        service.cancel(actor, projectId, groupId, messageId);
        verify(access).requireProjectAdmin(projectId, actor);
        verify(runs).update(isNull(), any());
    }

    @Test
    void ordinaryMemberCannotCancelAnotherUsersExecution() {
        messageFrom(UUID.randomUUID());
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"))
                .when(access).requireProjectAdmin(projectId, actor);
        assertThatThrownBy(() -> service.cancel(actor, projectId, groupId, messageId)).isInstanceOf(ApiException.class);
        verifyNoInteractions(runs);
    }

    @Test
    void inaccessibleMessageCannotCancelAnyRun() {
        when(messages.getMessage(actor, projectId, groupId, messageId))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "消息不存在"));
        assertThatThrownBy(() -> service.cancel(actor, projectId, groupId, messageId)).isInstanceOf(ApiException.class);
        verifyNoInteractions(access, runs);
    }

    private void messageFrom(UUID sender) {
        MessageResponse message = new MessageResponse();
        message.setSenderId(sender.toString());
        when(messages.getMessage(actor, projectId, groupId, messageId)).thenReturn(message);
    }
}
