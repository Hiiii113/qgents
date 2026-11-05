package qg.qgent.memory.tool;

import qg.qgent.support.TestIds;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.HttpStatus;

import qg.qgent.memory.dto.MemoryResponse;
import qg.qgent.memory.dto.MemoryCreateRequest;
import qg.qgent.memory.dto.MemoryUpdateRequest;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.service.MemoryService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryToolsTest {
    private final MemoryService memories = mock(MemoryService.class);
    private final Long actor = TestIds.next();
    private final Long projectId = TestIds.next();

    @Test
    void listsRelevantMemorySummariesWithinTheToolInputBudget() {
        List<MemoryResponse> visible = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            visible.add(memory("memory " + index, "unrelated"));
        }
        visible.add(memory("Database decision", "x".repeat(500)));
        when(memories.list(actor, projectId, null, null)).thenReturn(visible);
        MemoryTools tools = new MemoryTools(memories, actor, projectId);

        var result = tools.listMemories("database", null, null, 5);

        assertThat(ToolCallbacks.from(tools)).extracting(callback -> callback.getToolDefinition().name())
                .contains("list_memories");
        assertThat(result).containsEntry("ok", true).containsEntry("matchedCount", 1)
                .containsEntry("hasMore", false);
        @SuppressWarnings("unchecked")
        List<MemoryTools.MemorySummary> matches = (List<MemoryTools.MemorySummary>) result.get("memories");
        assertThat(matches).singleElement().satisfies(summary -> {
            assertThat(summary.title()).isEqualTo("Database decision");
            assertThat(summary.contentPreview()).hasSize(203).endsWith("...");
        });
        verify(memories).list(actor, projectId, null, null);
    }

    @Test
    void readsARequestedWindowFromMemoryVisibleToTheTriggeringUser() {
        Long memoryId = TestIds.next();
        when(memories.get(actor, projectId, memoryId)).thenReturn(memory("History", "0123456789"));
        MemoryTools tools = new MemoryTools(memories, actor, projectId);

        var result = tools.readMemory(memoryId.toString(), 4, 3);

        assertThat(ToolCallbacks.from(tools)).extracting(callback -> callback.getToolDefinition().name())
                .contains("read_memory");
        assertThat(result).containsEntry("ok", true).containsEntry("content", "456")
                .containsEntry("offsetChars", 4).containsEntry("totalChars", 10)
                .containsEntry("truncated", true).containsEntry("nextOffsetChars", 7);
        verify(memories).get(actor, projectId, memoryId);
    }

    @Test
    void createsMemoryThroughExistingServiceRulesAndReturnsItsActualState() {
        MemoryResponse draft = memory("Confirmed convention", "Use UTC timestamps");
        draft.setStatus("DRAFT");
        when(memories.create(eq(actor), eq(projectId), any(MemoryCreateRequest.class))).thenReturn(draft);
        MemoryTools tools = new MemoryTools(memories, actor, projectId);

        var result = tools.createMemory("Confirmed convention", "Use UTC timestamps",
                "ENGINEERING_DECISION", List.of("time"));

        assertThat(ToolCallbacks.from(tools)).extracting(callback -> callback.getToolDefinition().name())
                .contains("create_memory");
        assertThat(result).containsEntry("ok", true);
        var created = (MemoryTools.MemoryResult) result.get("memory");
        assertThat(created.title()).isEqualTo("Confirmed convention");
        assertThat(created.status()).isEqualTo("DRAFT");
        var request = org.mockito.ArgumentCaptor.forClass(MemoryCreateRequest.class);
        verify(memories).create(eq(actor), eq(projectId), request.capture());
        assertThat(request.getValue().getTitle()).isEqualTo("Confirmed convention");
        assertThat(request.getValue().getContent()).isEqualTo("Use UTC timestamps");
        assertThat(request.getValue().getTags()).containsExactly("time");
    }

    @Test
    void updatesAndRunsExistingStateActionsThroughMemoryService() {
        Long memoryId = TestIds.next();
        MemoryResponse pending = memory("Decision", "Updated body");
        pending.setStatus("PENDING_REVIEW");
        MemoryResponse approved = memory("Decision", "Updated body");
        approved.setStatus("APPROVED");
        MemoryResponse rejected = memory("Decision", "Updated body");
        rejected.setStatus("REJECTED");
        MemoryResponse archived = memory("Decision", "Updated body");
        archived.setStatus("ARCHIVED");
        when(memories.update(eq(actor), eq(projectId), eq(memoryId), any(MemoryUpdateRequest.class)))
                .thenReturn(pending);
        when(memories.submitReview(actor, projectId, memoryId)).thenReturn(pending);
        when(memories.approve(actor, projectId, memoryId)).thenReturn(approved);
        when(memories.reject(actor, projectId, memoryId, "Needs evidence")).thenReturn(rejected);
        when(memories.archive(actor, projectId, memoryId)).thenReturn(archived);
        MemoryTools tools = new MemoryTools(memories, actor, projectId);

        Map<String, Object> update = tools.updateMemory(memoryId.toString(), "Decision", "Updated body",
                null, List.of("confirmed"));
        Map<String, Object> submit = tools.submitMemoryReview(memoryId.toString());
        Map<String, Object> approve = tools.approveMemory(memoryId.toString());
        Map<String, Object> reject = tools.rejectMemory(memoryId.toString(), "Needs evidence");
        Map<String, Object> archive = tools.archiveMemory(memoryId.toString());

        assertThat(ToolCallbacks.from(tools)).extracting(callback -> callback.getToolDefinition().name())
                .contains("update_memory", "submit_memory_review", "approve_memory", "reject_memory", "archive_memory");
        assertMemoryState(update, "PENDING_REVIEW");
        assertMemoryState(submit, "PENDING_REVIEW");
        assertMemoryState(approve, "APPROVED");
        assertMemoryState(reject, "REJECTED");
        assertMemoryState(archive, "ARCHIVED");
        var request = org.mockito.ArgumentCaptor.forClass(MemoryUpdateRequest.class);
        verify(memories).update(eq(actor), eq(projectId), eq(memoryId), request.capture());
        assertThat(request.getValue().getTitle()).isEqualTo("Decision");
        assertThat(request.getValue().getContent()).isEqualTo("Updated body");
        assertThat(request.getValue().getCategory()).isNull();
        assertThat(request.getValue().getTags()).containsExactly("confirmed");
        verify(memories).submitReview(actor, projectId, memoryId);
        verify(memories).approve(actor, projectId, memoryId);
        verify(memories).reject(actor, projectId, memoryId, "Needs evidence");
        verify(memories).archive(actor, projectId, memoryId);
    }

    @Test
    void returnsTheExistingServiceRestrictionWhenAnApprovedMemoryCannotBeEdited() {
        Long memoryId = TestIds.next();
        when(memories.update(eq(actor), eq(projectId), eq(memoryId), any(MemoryUpdateRequest.class)))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "MEMORY_STATE_CONFLICT",
                        "Memory 当前状态不允许该操作: APPROVED"));
        MemoryTools tools = new MemoryTools(memories, actor, projectId);

        var result = tools.updateMemory(memoryId.toString(), null, "replacement", null, null);

        assertThat(result).containsEntry("ok", false)
                .containsEntry("error", "Memory 当前状态不允许该操作: APPROVED");
        verify(memories).update(eq(actor), eq(projectId), eq(memoryId), any(MemoryUpdateRequest.class));
    }

    private void assertMemoryState(Map<String, Object> result, String status) {
        assertThat(result).containsEntry("ok", true);
        assertThat(((MemoryTools.MemoryResult) result.get("memory")).status()).isEqualTo(status);
    }

    private MemoryResponse memory(String title, String content) {
        return new MemoryResponse(Long.toString(TestIds.next()), projectId.toString(), title, content,
                "ENGINEERING_DECISION", List.of("architecture"), "APPROVED", null, null, null, null, null);
    }
}
