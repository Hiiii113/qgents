package qg.qgent.memory.service;

import org.junit.jupiter.api.Test;

import qg.qgent.common.web.ApiException;
import qg.qgent.memory.entity.MemoryEntity;
import qg.qgent.memory.mapper.MemoryMapper;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.user.mapper.UserMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Memory 审核、归档和上下文预算业务测试。 */
class MemoryServiceTest {

    private final MemoryMapper memories = mock(MemoryMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final UserMapper users = mock(UserMapper.class);

    @Test
    void approveRejectsMemoryWhenApprovedContextBudgetWouldBeExceeded() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();
        MemoryEntity pending = memory(memoryId, projectId, actor, "标题", "正文", "PENDING_REVIEW");
        when(memories.selectByIdForUpdate(memoryId)).thenReturn(pending);
        when(memories.selectApprovedForUpdate(projectId)).thenReturn(List.of(memory(UUID.randomUUID(), projectId,
                actor, "已批准", "已有正文", "APPROVED")));

        ApiException error = assertThrows(ApiException.class,
                () -> service(10).approve(actor, projectId, memoryId));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.status());
        assertEquals("MEMORY_APPROVED_CONTEXT_LIMIT_EXCEEDED", error.code());
        assertEquals("PENDING_REVIEW", pending.getStatus());
        verify(projects).selectByIdForUpdate(projectId);
        verify(memories).selectApprovedForUpdate(projectId);
        verify(memories, never()).updateById(any(MemoryEntity.class));
    }

    @Test
    void approveAllowsMemoryAfterArchivedMemoryHasFreedBudget() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();
        MemoryEntity pending = memory(memoryId, projectId, actor, "标题", "正文", "PENDING_REVIEW");
        when(memories.selectByIdForUpdate(memoryId)).thenReturn(pending);
        // 已归档 Memory 不在聚合中，剩余已批准内容恰好可容纳本次批准。
        when(memories.selectApprovedForUpdate(projectId)).thenReturn(List.of(memory(UUID.randomUUID(), projectId,
                actor, "已批准", "内容", "APPROVED")));
        when(memories.selectById(memoryId)).thenReturn(pending);

        var response = service(10).approve(actor, projectId, memoryId);

        assertEquals("APPROVED", response.getStatus());
        verify(projects).selectByIdForUpdate(projectId);
        verify(memories).selectApprovedForUpdate(projectId);
        verify(memories).updateById(pending);
    }

    @Test
    void archiveLocksProjectBeforeReleasingApprovedContextBudget() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();
        MemoryEntity approved = memory(memoryId, projectId, actor, "标题", "正文", "APPROVED");
        when(memories.selectByIdForUpdate(memoryId)).thenReturn(approved);
        when(memories.selectById(memoryId)).thenReturn(approved);

        var response = service(10).archive(actor, projectId, memoryId);

        assertEquals("ARCHIVED", response.getStatus());
        verify(projects).selectByIdForUpdate(projectId);
        verify(memories).selectByIdForUpdate(memoryId);
        verify(memories).updateById(approved);
    }

    private MemoryService service(int maxApprovedMemoryChars) {
        return new MemoryService(memories, projects, access, users, mock(EventService.class), maxApprovedMemoryChars);
    }

    private MemoryEntity memory(UUID memoryId, UUID projectId, UUID actor, String title, String content, String status) {
        MemoryEntity value = new MemoryEntity();
        value.setId(memoryId);
        value.setProjectId(projectId);
        value.setCreatedBy(actor);
        value.setTitle(title);
        value.setContent(content);
        value.setStatus(status);
        value.setCategory("ENGINEERING_DECISION");
        return value;
    }
}
