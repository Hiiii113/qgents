package qg.qgent.harness.context;

import org.junit.jupiter.api.Test;

import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.service.ChatHistoryService;
import qg.qgent.chat.service.GroupService;
import qg.qgent.memory.dto.GroupMemoryResponse;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.service.AgentMemoryService;
import qg.qgent.memory.service.GroupMemoryService;
import qg.qgent.memory.service.ProjectMemoryService;
import qg.qgent.skill.dto.ContextSkill;
import qg.qgent.skill.service.SkillService;
import qg.qgent.support.TestIds;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 群上下文同时携带项目记忆、当前群记忆、当前 Agent 私有记忆、Skill 目录和近期原始消息。 */
class ContextServiceTest {

    private final GroupService groups = mock(GroupService.class);
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final SkillService skills = mock(SkillService.class);
    private final ProjectMemoryService projectMemories = mock(ProjectMemoryService.class);
    private final GroupMemoryService groupMemories = mock(GroupMemoryService.class);
    private final AgentMemoryService agentMemories = mock(AgentMemoryService.class);
    private final ContextService service = new ContextService(groups, history, skills, projectMemories, groupMemories,
            agentMemories);

    @Test
    void composesProjectMemoryGroupMemoryAndRecentMessages() {
        Long actor = TestIds.next();
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long agentId = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setName("登录改造");
        when(groups.requireGroupMember(projectId, groupId, actor)).thenReturn(group);
        when(history.recentMessages(actor, projectId, groupId, 30))
                .thenReturn(List.of(new ContextMessage(1L, "TEXT", "USER", "1", "先做邮箱登录", null, null, null, null)));
        when(skills.publishedCatalog(actor, projectId)).thenReturn(List.of(new ContextSkill(TestIds.next(), "登录", "登录规范")));
        when(projectMemories.get(actor, projectId))
                .thenReturn(new ProjectMemoryResponse(projectId.toString(), "# 项目记忆", "v1", null));
        when(groupMemories.read(projectId, groupId))
                .thenReturn(new GroupMemoryResponse(projectId.toString(), groupId.toString(), "# 群记忆\n- 结论：邮箱登录", null));
        when(agentMemories.view(projectId, agentId)).thenReturn("");

        GroupContext context = service.buildForGroup(actor, projectId, groupId, agentId, 30);

        assertThat(context.getTitle()).isEqualTo("登录改造");
        assertThat(context.getProjectMemory()).isEqualTo("# 项目记忆");
        assertThat(context.getGroupMemory()).isEqualTo("# 群记忆\n- 结论：邮箱登录");
        assertThat(context.getConversation()).extracting(ContextMessage::getText).containsExactly("先做邮箱登录");
        assertThat(context.getSkills()).extracting(ContextSkill::getName).containsExactly("登录");
    }

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
        when(groupMemories.read(projectId, groupId))
                .thenReturn(new GroupMemoryResponse(projectId.toString(), groupId.toString(), "# 群记忆\n- 结论：邮箱登录", null));
        when(agentMemories.view(projectId, agentId)).thenReturn("# 分工");

        GroupContext built = service.buildForGroup(actor, projectId, groupId, agentId, 30);

        assertThat(built.getProjectMemory()).isEqualTo("# 项目约定");
        assertThat(built.getGroupMemory()).isEqualTo("# 群记忆\n- 结论：邮箱登录");
        assertThat(built.getAgentMemory()).isEqualTo("# 分工");
        assertThat(built.getConversation()).isEqualTo(recent);
    }
}
