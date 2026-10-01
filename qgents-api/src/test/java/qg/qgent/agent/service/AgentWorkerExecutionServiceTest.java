package qg.qgent.agent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import qg.qgent.agent.entity.AgentRunEntity;
import qg.qgent.agent.entity.AgentWorkerExecutionEntity;
import qg.qgent.agent.mapper.AgentRunMapper;
import qg.qgent.agent.mapper.AgentWorkerExecutionMapper;
import qg.qgent.sandbox.dto.WorkerToolExecution;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

class AgentWorkerExecutionServiceTest {

    @Test
    void persistsExecutionIdImmediatelyWithSanitizedFailureSummary() {
        AgentWorkerExecutionMapper executions = mock(AgentWorkerExecutionMapper.class);
        AgentRunMapper runs = mock(AgentRunMapper.class);
        AgentWorkerExecutionService service = new AgentWorkerExecutionService(executions, runs);
        UUID runId = UUID.randomUUID();
        AgentRunEntity run = new AgentRunEntity();
        run.setId(runId);
        run.setProjectId(UUID.randomUUID());
        run.setWorkspaceId(UUID.randomUUID());
        when(runs.selectById(runId)).thenReturn(run);

        WorkerToolExecution execution = new WorkerToolExecution();
        execution.setId(UUID.randomUUID());
        execution.setSandboxId(UUID.randomUUID());
        execution.setTool("file.patch");
        execution.setStatus("FAILED");
        execution.setFailureCode("FILE_PATCH_FAILED");
        execution.setFailureReason("token=secret C:\\Users\\someone\\repo 补丁不匹配");

        service.record(runId, execution);

        ArgumentCaptor<AgentWorkerExecutionEntity> captured =
                ArgumentCaptor.forClass(AgentWorkerExecutionEntity.class);
        verify(executions).insert(captured.capture());
        assertEquals(runId, captured.getValue().getAgentRunId());
        assertEquals(run.getWorkspaceId(), captured.getValue().getWorkspaceId());
        assertEquals("FILE_PATCH_FAILED", captured.getValue().getFailureCode());
        assertFalse(captured.getValue().getFailureReason().contains("secret"));
        assertFalse(captured.getValue().getFailureReason().contains("C:\\Users"));
    }

    @Test
    void retainsWorkerFailureCodeAndSanitizesItsDetail() {
        AgentWorkerExecutionMapper executions = mock(AgentWorkerExecutionMapper.class);
        AgentRunMapper runs = mock(AgentRunMapper.class);
        AgentWorkerExecutionService service = new AgentWorkerExecutionService(executions, runs);
        UUID runId = UUID.randomUUID();
        AgentRunEntity run = new AgentRunEntity();
        run.setId(runId);
        run.setProjectId(UUID.randomUUID());
        when(runs.selectById(runId)).thenReturn(run);

        WorkerToolExecution execution = new WorkerToolExecution();
        execution.setId(UUID.randomUUID());
        execution.setStatus("FAILED");
        execution.setFailureCode("WORKER_PROVIDER_ERROR");
        execution.setFailureReason("Access denied at https://provider.example/error");

        service.record(runId, execution);

        ArgumentCaptor<AgentWorkerExecutionEntity> captured =
                ArgumentCaptor.forClass(AgentWorkerExecutionEntity.class);
        verify(executions).insert(captured.capture());
        assertEquals("WORKER_PROVIDER_ERROR", captured.getValue().getFailureCode());
        assertEquals("Access denied at [endpoint omitted]", captured.getValue().getFailureReason());
    }
}
