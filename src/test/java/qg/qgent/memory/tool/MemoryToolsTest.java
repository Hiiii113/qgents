package qg.qgent.memory.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.HttpStatus;

import qg.qgent.common.web.ApiException;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.service.ProjectMemoryService;
import qg.qgent.support.TestIds;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 项目记忆工具：读取返回正文与版本，写入透传版本，冲突转为工具错误。 */
class MemoryToolsTest {

    private final ProjectMemoryService memories = mock(ProjectMemoryService.class);
    private final Long actor = TestIds.next();
    private final Long projectId = TestIds.next();
    private final MemoryTools tools = new MemoryTools(memories, actor, projectId);

    @Test
    void exposesOnlyReadAndWriteProjectMemory() {
        assertThat(ToolCallbacks.from(tools)).extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("read_project_memory", "write_project_memory");
    }

    @Test
    void readReturnsContentAndVersion() {
        when(memories.get(actor, projectId))
                .thenReturn(new ProjectMemoryResponse(projectId.toString(), "# 约定", "v1", LocalDateTime.now()));

        Map<String, Object> result = tools.readProjectMemory();

        assertThat(result).containsEntry("ok", true).containsEntry("content", "# 约定")
                .containsEntry("version", "v1");
    }

    @Test
    void writeForwardsContentWithReadVersion() {
        when(memories.save(actor, projectId, "# 新约定", "v1"))
                .thenReturn(new ProjectMemoryResponse(projectId.toString(), "# 新约定", "v2", LocalDateTime.now()));

        Map<String, Object> result = tools.writeProjectMemory("# 新约定", "v1");

        assertThat(result).containsEntry("ok", true).containsEntry("version", "v2");
        verify(memories).save(actor, projectId, "# 新约定", "v1");
    }

    @Test
    void versionConflictBecomesToolError() {
        when(memories.save(eq(actor), eq(projectId), any(), eq("stale")))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "MEMORY_VERSION_CONFLICT", "项目记忆已被修改"));

        Map<String, Object> result = tools.writeProjectMemory("# 新约定", "stale");

        assertThat(result).containsEntry("ok", false).containsEntry("error", "项目记忆已被修改");
    }
}
