package qg.qgent.memory.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.HttpStatus;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.entity.ProjectMemoryProposalEntity;
import qg.qgent.memory.service.ProjectMemoryProposalService;
import qg.qgent.support.TestIds;

/** 项目记忆候选与审批工具：候选绑定当前运行，审批只使用触发消息，服务端拒绝转为工具错误。 */
class MemoryApprovalToolsTest {

    private final ProjectMemoryProposalService proposals = mock(ProjectMemoryProposalService.class);
    private final Long actor = TestIds.next();
    private final Long projectId = TestIds.next();
    private final Long groupId = TestIds.next();
    private final Long runId = TestIds.next();
    private final Long approvalMessageId = TestIds.next();
    private final MemoryApprovalTools tools =
            new MemoryApprovalTools(proposals, actor, projectId, groupId, runId, approvalMessageId);

    @Test
    void exposesCandidateAndApprovalTools() {
        assertThat(ToolCallbacks.from(tools))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("propose_project_memory", "approve_project_memory");
    }

    @Test
    void proposalIsBoundToCurrentRunAndGroup() {
        ProjectMemoryProposalEntity proposal = new ProjectMemoryProposalEntity();
        proposal.setId(TestIds.next());
        proposal.setStatus("PENDING");
        when(proposals.propose(actor, projectId, groupId, runId, "# 新约定")).thenReturn(proposal);

        Map<String, Object> result = tools.proposeProjectMemory("# 新约定");

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("proposalId", proposal.getId().toString())
                .containsEntry("status", "PENDING");
        verify(proposals).propose(actor, projectId, groupId, runId, "# 新约定");
    }

    @Test
    void blankCandidateIsRejectedWithoutTouchingTheService() {
        Map<String, Object> result = tools.proposeProjectMemory("  ");

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("errorCode", "INVALID_ARGUMENT");
        verify(proposals, never()).propose(any(), any(), any(), any(), any());
    }

    @Test
    void approvalUsesOnlyTheTriggerMessageAndReturnsAppliedMemory() {
        when(proposals.approve(actor, projectId, groupId, approvalMessageId))
                .thenReturn(
                        new ProjectMemoryResponse(
                                projectId.toString(), "# 新约定", "v2", LocalDateTime.now()));

        Map<String, Object> result = tools.approveProjectMemory();

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("content", "# 新约定")
                .containsEntry("version", "v2");
    }

    @Test
    void approvalRejectionBecomesToolError() {
        when(proposals.approve(actor, projectId, groupId, approvalMessageId))
                .thenThrow(
                        new ApiException(
                                HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"));

        Map<String, Object> result = tools.approveProjectMemory();

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("errorCode", "PROJECT_ADMIN_REQUIRED");
    }
}
