package qg.qgent.memory.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import qg.qgent.common.web.ApiException;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.support.TestIds;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Agent 私有记忆：写在所属项目目录、按 Agent 隔离、各群共享一篇、受上下文预算约束。 */
class AgentMemoryServiceTest {

    private static final int MAX_CHARS = 100;

    @TempDir
    Path root;

    private final Long projectId = TestIds.next();
    private final Long agentId = TestIds.next();
    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        service = new AgentMemoryService(new ProjectDirectoryService(root.toString(), root.toString()), MAX_CHARS);
    }

    @Test
    void firstReadIsEmptyAndWritesNothing() {
        assertThat(service.view(projectId, agentId)).isEmpty();
        assertThat(root.resolve(projectId.toString()).resolve("agent-memory")).doesNotExist();
    }

    @Test
    void savedNotesAreReadBackAndLiveInTheProjectDirectory() throws Exception {
        service.save(projectId, agentId, "# 分工\n- 你负责接口");

        assertThat(service.view(projectId, agentId)).isEqualTo("# 分工\n- 你负责接口");
        assertThat(Files.readString(root.resolve(projectId.toString()).resolve("agent-memory")
                .resolve(agentId.toString()).resolve("memory.md"), StandardCharsets.UTF_8))
                .isEqualTo("# 分工\n- 你负责接口");
    }

    @Test
    void agentsAndProjectsKeepSeparateMemory() {
        Long otherAgent = TestIds.next();
        Long otherProject = TestIds.next();
        service.save(projectId, agentId, "属于这个 Agent");

        assertThat(service.view(projectId, otherAgent)).isEmpty();
        assertThat(service.view(otherProject, agentId)).isEmpty();
        assertThat(service.view(projectId, agentId)).isEqualTo("属于这个 Agent");
    }

    @Test
    void oversizedMemoryIsRejectedWithoutOverwritingTheStoredOne() {
        service.save(projectId, agentId, "原有分工");

        ApiException error = assertThrows(ApiException.class,
                () -> service.save(projectId, agentId, "x".repeat(MAX_CHARS + 1)));

        assertThat(error.code()).isEqualTo("MEMORY_CONTEXT_LIMIT_EXCEEDED");
        assertThat(service.view(projectId, agentId)).isEqualTo("原有分工");
    }
}
