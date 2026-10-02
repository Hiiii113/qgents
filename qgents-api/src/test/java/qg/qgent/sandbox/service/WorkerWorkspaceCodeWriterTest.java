package qg.qgent.sandbox.service;

import org.junit.jupiter.api.Test;
import qg.qgent.sandbox.client.SandboxWorkerClient;
import qg.qgent.sandbox.config.SandboxWorkerProperties;
import qg.qgent.sandbox.dto.WorkerToolExecution;
import qg.qgent.sandbox.dto.WorkerToolExecutionRequest;
import qg.qgent.agent.service.AgentWorkerExecutionService;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkerWorkspaceCodeWriterTest {
    private final UUID id=UUID.randomUUID(), executionId=UUID.randomUUID();
    private final SandboxWorkerClient client=mock(SandboxWorkerClient.class);
    private final SandboxSessionManager sessions=mock(SandboxSessionManager.class);
    private final WorkerWorkspaceCodeWriter writer=new WorkerWorkspaceCodeWriter(client,sessions,new SandboxWorkerProperties(),mock(AgentWorkerExecutionService.class));
    private void execution(String status,String code) {
        when(sessions.require(id)).thenReturn(new SandboxSession(UUID.randomUUID(),id,UUID.randomUUID(),"key"));
        var execution=new WorkerToolExecution(); execution.setId(executionId); execution.setStatus(status);
        execution.setResult(Map.of("sha256","a".repeat(64),"changed",true)); execution.setFailureCode(code); execution.setFailureReason("重新读取");
        when(client.submitToolExecution(any(),any())).thenReturn(execution); when(client.getToolExecution(executionId)).thenReturn(execution);
    }
    @Test void newFileUsesCreateModeWithoutReadingCurrentHash() {
        execution("SUCCEEDED",null);
        assertThat(writer.writeFile(id,".gitignore","x").isOk()).isTrue();
        var request=org.mockito.ArgumentCaptor.forClass(WorkerToolExecutionRequest.class); verify(client).submitToolExecution(any(),request.capture());
        assertThat(request.getValue().getArguments()).containsEntry("mode","create");
        verify(client,times(1)).submitToolExecution(any(),any());
    }
    @Test void replacementPassesObservedVersionAndMode() {
        execution("SUCCEEDED",null); writer.replaceFile(id,"x","b".repeat(64),"new");
        var request=org.mockito.ArgumentCaptor.forClass(WorkerToolExecutionRequest.class); verify(client).submitToolExecution(any(),request.capture());
        assertThat(request.getValue().getArguments()).containsEntry("mode","replace").containsEntry("expectedHash","b".repeat(64));
    }
    @Test void writeConflictAndEditAmbiguityRemainModelRepairable() {
        execution("FAILED","FILE_ALREADY_EXISTS");
        assertThat(writer.writeFile(id,".gitignore","x").getFailureCode()).isEqualTo("FILE_ALREADY_EXISTS");
        execution("FAILED","EDIT_TEXT_AMBIGUOUS");
        assertThat(writer.editFile(id,"x","a".repeat(64),"old","new",false).getFailureCode()).isEqualTo("EDIT_TEXT_AMBIGUOUS");
    }
    @Test void oversizedWriteIsRejectedBeforeWorkerSubmission() {
        assertThat(writer.writeFile(id,"x","x".repeat(256*1024+1)).isOk()).isFalse(); verifyNoInteractions(client,sessions);
    }
    @Test void unavailableWorkerRemainsInfrastructureFailure() {
        when(sessions.require(id)).thenThrow(new IllegalStateException("Worker 不可用"));
        assertThat(writer.patchFile(id,"x","a".repeat(64),"patch").isInfrastructureFailure()).isTrue();
    }
}
