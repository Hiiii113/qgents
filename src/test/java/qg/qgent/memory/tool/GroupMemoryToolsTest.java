package qg.qgent.memory.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.common.web.ApiException;
import qg.qgent.harness.tool.ToolRegistry;
import qg.qgent.memory.service.GroupMemoryService;
import qg.qgent.memory.service.GroupMemorySnapshot;
import qg.qgent.support.TestIds;

/** 群记忆工具：群成员可直接读取、按片段修改或整篇重写群记忆。 */
class GroupMemoryToolsTest {

    private final GroupMemoryService memories = mock(GroupMemoryService.class);
    private final Long actor = TestIds.next();
    private final Long projectId = TestIds.next();
    private final Long groupId = TestIds.next();
    private final GroupMemoryTools tools =
            new GroupMemoryTools(memories, actor, projectId, groupId);

    @Test
    void exposesReadEditAndWriteButNoCorrectionRequest() {
        ToolRegistry registry = new ToolRegistry(new ObjectMapper());
        registry.register(tools);
        assertThat(registry.callbacks())
                .extracting(callback -> callback.name())
                .containsExactlyInAnyOrder(
                        "read_group_memory", "edit_group_memory", "write_group_memory");
    }

    @Test
    void readReturnsLatestContentAndRevisionAfterMemberCheck() {
        when(memories.readWithRevision(projectId, groupId))
                .thenReturn(
                        new GroupMemorySnapshot(
                                "# 群记忆", 4L, LocalDateTime.of(2026, 8, 18, 10, 0)));

        Map<String, Object> result = tools.readGroupMemory();

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("content", "# 群记忆")
                .containsEntry("revision", 4L)
                .containsEntry("updatedAt", "2026-08-18T10:00");
        verify(memories).requireMember(actor, projectId, groupId);
    }

    @Test
    void editReplacesAnchorAndReportsNewRevision() {
        when(memories.edit(projectId, groupId, "结论：A", "结论：B")).thenReturn(5L);

        Map<String, Object> result = tools.editGroupMemory("结论：A", "结论：B");

        assertThat(result).containsEntry("ok", true).containsEntry("revision", 5L);
        verify(memories).requireMember(actor, projectId, groupId);
        verify(memories).edit(projectId, groupId, "结论：A", "结论：B");
    }

    @Test
    void writeRewritesWholeMemoryAndReportsNewRevision() {
        when(memories.writeByAgent(projectId, groupId, "# 群记忆")).thenReturn(1L);

        Map<String, Object> result = tools.writeGroupMemory("# 群记忆");

        assertThat(result).containsEntry("ok", true).containsEntry("revision", 1L);
        verify(memories).requireMember(actor, projectId, groupId);
        verify(memories).writeByAgent(projectId, groupId, "# 群记忆");
    }

    @Test
    void rejectsBlankArgumentsWithoutTouchingMemory() {
        assertThat(tools.editGroupMemory("", "结论：B"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(tools.editGroupMemory("结论：A", null))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        assertThat(tools.writeGroupMemory("  "))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        verifyNoInteractions(memories);
    }

    @Test
    void membershipDenialBecomesToolError() {
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问"))
                .when(memories)
                .requireMember(actor, projectId, groupId);

        assertThat(tools.editGroupMemory("结论：A", "结论：B"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "GROUP_NOT_FOUND");
        verify(memories, org.mockito.Mockito.never())
                .edit(anyLong(), anyLong(), any(), any());
    }

    @Test
    void writeFailureBecomesToolError() {
        doThrow(new IllegalStateException("记忆写入失败"))
                .when(memories)
                .writeByAgent(anyLong(), anyLong(), any());

        assertThat(tools.writeGroupMemory("# 群记忆"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "GROUP_MEMORY_UNAVAILABLE");
    }
}
