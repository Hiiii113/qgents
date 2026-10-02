package qg.qgent.sandbox.service;

import org.junit.jupiter.api.Test;
import qg.qgent.sandbox.client.SandboxWorkerClient;
import qg.qgent.sandbox.config.SandboxWorkerProperties;
import qg.qgent.sandbox.dto.WorkerToolExecution;
import qg.qgent.agent.service.AgentWorkerExecutionService;
import java.util.UUID;
import java.util.Map;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkerWorkspaceCommandsTest {
    private final UUID id=UUID.randomUUID(), sandbox=UUID.randomUUID(), execution=UUID.randomUUID();
    private final SandboxWorkerClient client=mock(SandboxWorkerClient.class);
    private final SandboxSessionManager sessions=mock(SandboxSessionManager.class);
    private final WorkerWorkspaceCommands commands=new WorkerWorkspaceCommands(client,sessions,new SandboxWorkerProperties(),mock(AgentWorkerExecutionService.class));
    private WorkerToolExecution execution(String status,Integer exitCode) {
        when(sessions.require(id)).thenReturn(new SandboxSession(UUID.randomUUID(),id,sandbox,"key"));
        var value=new WorkerToolExecution();value.setId(execution);value.setSandboxId(sandbox);value.setTool("command.run");value.setStatus(status);value.setExitCode(exitCode);value.setResult(Map.of());
        when(client.submitToolExecution(eq(sandbox),any())).thenReturn(value);when(client.getToolExecution(execution)).thenReturn(value);return value;
    }
    @Test void immediateWaitReturnsRunningHandleWithoutStartingAgain() {
        execution("RUNNING",null);
        assertThat(commands.start(id,".",List.of("npm","test"),600,0)).containsEntry("running",true).containsEntry("executionId",execution.toString());
        verify(client,times(1)).submitToolExecution(any(),any());
    }
    @Test void nonzeroExitIsReturnedAsCompletedCommand() {
        execution("SUCCEEDED",5);
        assertThat(commands.start(id,".",List.of("npm","test"),600,0)).containsEntry("ok",true).containsEntry("exitCode",5).containsEntry("running",false);
    }
    @Test void outputUsesByteCursorsAndRenewsEnvironment() {
        execution("RUNNING",null);when(client.getCommandOutput(execution,30,40,16000)).thenReturn(Map.of("stdout",Map.of("text","next","nextOffset",34L)));
        assertThat(commands.output(id,execution,30,40,16000,0)).containsEntry("running",true).containsKey("stdout");
        verify(client).getCommandOutput(execution,30,40,16000);verify(sessions).renewWriteLease(id);verify(client).renewSandbox(sandbox);
        verify(client,never()).submitToolExecution(any(),any());
    }
    @Test void foreignExecutionCannotBeReadOrCancelled() {
        var value=execution("RUNNING",null);value.setSandboxId(UUID.randomUUID());
        assertThatThrownBy(() -> commands.output(id,execution,0,0,16000,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commands.cancel(id,execution)).isInstanceOf(IllegalArgumentException.class);
        verify(client,never()).cancelToolExecution(any());verify(client,never()).getCommandOutput(any(),anyLong(),anyLong(),anyInt());
    }
    @Test void cancelActiveCommandAndRepeatedCancelTerminalCommand() {
        execution("RUNNING",null);var cancelled=new WorkerToolExecution();cancelled.setId(execution);cancelled.setStatus("CANCELLED");
        when(client.cancelToolExecution(execution)).thenReturn(cancelled);
        assertThat(commands.cancel(id,execution)).containsEntry("status","CANCELLED").containsEntry("running",false);
        execution("SUCCEEDED",0);assertThat(commands.cancel(id,execution)).containsEntry("exitCode",0);
        verify(client,times(1)).cancelToolExecution(execution);
    }
}
