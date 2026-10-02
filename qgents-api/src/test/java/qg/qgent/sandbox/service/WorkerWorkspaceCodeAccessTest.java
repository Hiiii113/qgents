package qg.qgent.sandbox.service;

import org.junit.jupiter.api.Test;
import qg.qgent.sandbox.client.SandboxWorkerClient;
import qg.qgent.sandbox.config.SandboxWorkerProperties;
import qg.qgent.sandbox.dto.WorkerToolExecution;
import qg.qgent.sandbox.dto.WorkerToolExecutionRequest;
import qg.qgent.agent.service.AgentWorkerExecutionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkerWorkspaceCodeAccessTest {
    private final UUID id=UUID.randomUUID(), executionId=UUID.randomUUID();
    private final SandboxWorkerClient client=mock(SandboxWorkerClient.class);
    private final SandboxSessionManager sessions=mock(SandboxSessionManager.class);
    private final WorkerWorkspaceCodeAccess access=new WorkerWorkspaceCodeAccess(client,sessions,new SandboxWorkerProperties(),mock(AgentWorkerExecutionService.class));
    private void execution(String status, Map<String,Object> result) {
        when(sessions.require(id)).thenReturn(new SandboxSession(UUID.randomUUID(),id,UUID.randomUUID(),"key"));
        var execution=new WorkerToolExecution(); execution.setId(executionId); execution.setStatus(status); execution.setResult(result);
        execution.setFailureCode("PROCESS_EXIT_NONZERO"); execution.setFailureReason("invalid regex");
        when(client.submitToolExecution(any(),any())).thenReturn(execution); when(client.getToolExecution(executionId)).thenReturn(execution);
    }
    @Test void preservesRequestedWindowAndDoesNotFetchRemainingPages() {
        execution("SUCCEEDED",Map.of("lines",List.of(Map.of("number",301,"text","code")),"truncated",true,"nextLine",302));
        assertThat(access.readFile(id,"x.java",301,1)).containsEntry("nextLine",302).containsEntry("truncated",true);
        var request=org.mockito.ArgumentCaptor.forClass(WorkerToolExecutionRequest.class);
        verify(client).submitToolExecution(any(),request.capture());
        assertThat(request.getValue().getArguments()).containsEntry("startLine",301).containsEntry("lineCount",1);
    }
    @Test void discoveryIsOneWorkerExecution() {
        execution("SUCCEEDED",Map.of("files",List.of(".gitignore","src/x.java"),"truncated",false));
        assertThat(access.listFiles(id,".","**",200)).containsEntry("files",List.of(".gitignore","src/x.java"));
        verify(client,times(1)).submitToolExecution(any(),any());
    }
    @Test void failedSearchPreservesError() {
        execution("FAILED",Map.of());
        assertThat(access.searchCode(id,"[",".","**",100)).containsEntry("ok",false).containsEntry("errorCode","PROCESS_EXIT_NONZERO");
    }
}
