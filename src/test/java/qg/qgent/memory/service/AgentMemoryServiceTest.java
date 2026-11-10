package qg.qgent.memory.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import qg.qgent.chat.service.GroupService;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.dto.AgentMemoryView;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.support.TestIds;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Agent 私有记忆：写在所属项目目录、按 Agent 隔离、来源群受众受限、受上下文预算约束。 */
class AgentMemoryServiceTest {

    private static final int MAX_CHARS = 100;

    @TempDir
    Path root;

    private final GroupService groups = mock(GroupService.class);
    private final Long projectId = TestIds.next();
    private final Long agentId = TestIds.next();
    private final Long groupId = TestIds.next();
    private final Long otherGroupId = TestIds.next();
    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        service = new AgentMemoryService(groups,
                new ProjectDirectoryService(root.toString(), root.toString()), MAX_CHARS);
    }

    @Test
    void firstReadIsEmptyAndWritesNothing() {
        assertThat(service.view(projectId, agentId, groupId).merged()).isEmpty();
        assertThat(root.resolve(projectId.toString()).resolve("agent-memory")).doesNotExist();
    }

    @Test
    void savedNotesAreReadBackAndLiveInTheProjectDirectory() throws Exception {
        service.save(projectId, agentId, groupId, "# 分工\n- 你负责接口");

        assertThat(service.view(projectId, agentId, groupId).content()).isEqualTo("# 分工\n- 你负责接口");
        assertThat(Files.readString(root.resolve(projectId.toString()).resolve("agent-memory")
                .resolve(agentId.toString()).resolve(groupId + ".md"), StandardCharsets.UTF_8))
                .isEqualTo("# 分工\n- 你负责接口");
    }

    @Test
    void otherGroupsNotesStayOutOfThisGroup() {
        service.save(projectId, agentId, otherGroupId, "受限群里的要求");
        when(groups.audienceCoveredBy(projectId, otherGroupId, groupId)).thenReturn(false);

        AgentMemoryView view = service.view(projectId, agentId, groupId);

        assertThat(view.content()).isEmpty();
        assertThat(view.inherited()).isEmpty();
    }

    @Test
    void notesFromAGroupWhoseAudienceCoversThisGroupAreInherited() {
        service.save(projectId, agentId, otherGroupId, "# 你的分工");
        when(groups.audienceCoveredBy(projectId, otherGroupId, groupId)).thenReturn(true);

        AgentMemoryView view = service.view(projectId, agentId, groupId);

        assertThat(view.content()).isEmpty();
        assertThat(view.inherited()).isEqualTo("# 你的分工");
        assertThat(view.merged()).isEqualTo("# 你的分工");
    }

    @Test
    void inheritedNotesCountTowardTheContextBudget() {
        service.save(projectId, agentId, otherGroupId, "x".repeat(MAX_CHARS));
        when(groups.audienceCoveredBy(projectId, otherGroupId, groupId)).thenReturn(true);

        ApiException error = assertThrows(ApiException.class,
                () -> service.save(projectId, agentId, groupId, "新生效的要求"));

        assertThat(error.code()).isEqualTo("MEMORY_CONTEXT_LIMIT_EXCEEDED");
        assertThat(service.view(projectId, agentId, groupId).content()).isEmpty();
    }

    @Test
    void agentsAndProjectsKeepSeparateMemory() {
        Long otherAgent = TestIds.next();
        Long otherProject = TestIds.next();
        service.save(projectId, agentId, groupId, "属于这个 Agent");

        assertThat(service.view(projectId, otherAgent, groupId).content()).isEmpty();
        assertThat(service.view(otherProject, agentId, groupId).content()).isEmpty();
        assertThat(service.view(projectId, agentId, groupId).content()).isEqualTo("属于这个 Agent");
    }
}
