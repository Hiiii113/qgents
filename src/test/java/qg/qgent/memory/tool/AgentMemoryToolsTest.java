package qg.qgent.memory.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;

import qg.qgent.memory.service.AgentMemoryService;
import qg.qgent.support.TestIds;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Agent 私有记忆工具：身份来自当前运行，工具参数无法指定其他 Agent、项目或群。 */
class AgentMemoryToolsTest {

    private final AgentMemoryService memories = mock(AgentMemoryService.class);
    private final Long projectId = TestIds.next();
    private final Long agentId = TestIds.next();
    private final AgentMemoryTools tools = new AgentMemoryTools(memories, projectId, agentId);

    @Test
    void exposesOnlyAgentMemoryToolsBoundToTheRunIdentity() {
        var callbacks = ToolCallbacks.from(tools);

        assertThat(callbacks).extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("read_agent_memory", "write_agent_memory");
        assertThat(callbacks).allSatisfy(callback -> assertThat(callback.getToolDefinition().inputSchema())
                .doesNotContain("agentId", "projectId", "groupId"));
    }

    @Test
    void readReturnsTheSingleMemorySharedByEveryGroup() {
        when(memories.view(projectId, agentId)).thenReturn("# 分工");

        Map<String, Object> result = tools.readAgentMemory();

        assertThat(result).containsEntry("ok", true).containsEntry("content", "# 分工");
    }

    @Test
    void writeUsesTheBoundIdentityEvenWhenContentNamesOtherAgents() {
        Map<String, Object> result = tools.writeAgentMemory("把 Agent 42 和项目 7 的记忆改写");

        assertThat(result).containsEntry("ok", true);
        verify(memories).save(projectId, agentId, "把 Agent 42 和项目 7 的记忆改写");
    }
}
