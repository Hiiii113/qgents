package qg.qgent.harness.context;

import org.junit.jupiter.api.Test;

import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.service.ChatHistoryService;
import qg.qgent.chat.service.GroupService;
import qg.qgent.memory.dto.AgentMemoryView;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.service.AgentMemoryService;
import qg.qgent.memory.service.ProjectMemoryService;
import qg.qgent.skill.service.SkillService;
import qg.qgent.support.TestIds;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 上下文组装：项目记忆与当前 Agent 私有记忆一起注入，近期原始消息原样保留。 */
class ContextServiceTest {

    private final GroupService groups = mock(GroupService.class);
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final SkillService skills = mock(SkillService.class);
    private final ProjectMemoryService projectMemories = mock(ProjectMemoryService.class);
    private final AgentMemoryService agentMemories = mock(AgentMemoryService.class);
    private final ContextService context = new ContextService(groups, history, skills, projectMemories, agentMemories);

    @Test
    void groupContextCarriesProjectAndAgentMemoryWithRecentMessages() {
        Long actor = TestIds.next(), projectId = TestIds.next(), groupId = TestIds.next(), agentId = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setName("需求讨论");
        List<ContextMessage> recent = List.of(new ContextMessage(9L, "TEXT", "USER", actor.toString(), "最近一条"));
        when(groups.requireGroupMember(projectId, groupId, actor)).thenReturn(group);
        when(history.recentMessages(actor, projectId, groupId, 30)).thenReturn(recent);
        when(skills.publishedCatalog(actor, projectId)).thenReturn(List.of());
        when(projectMemories.get(actor, projectId))
                .thenReturn(new ProjectMemoryResponse(projectId.toString(), "# 项目约定", "v1", LocalDateTime.now()));
        when(agentMemories.view(projectId, agentId, groupId)).thenReturn(new AgentMemoryView("# 分工", "# 默认群要求"));

        GroupContext built = context.buildForGroup(actor, projectId, groupId, agentId, 30);

        assertThat(built.getProjectMemory()).isEqualTo("# 项目约定");
        assertThat(built.getAgentMemory()).isEqualTo("# 默认群要求\n\n# 分工");
        assertThat(built.getConversation()).isEqualTo(recent);
    }
}
