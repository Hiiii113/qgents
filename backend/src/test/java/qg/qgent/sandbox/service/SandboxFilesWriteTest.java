package qg.qgent.sandbox.service;

import org.junit.jupiter.api.Test;
import qg.qgent.sandbox.execution.dto.ToolExecutionResponse;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SandboxFilesWriteTest {
    private final UUID id=UUID.randomUUID(), executionId=UUID.randomUUID();
    private final SandboxToolService tools=mock(SandboxToolService.class);
    private final SandboxFiles writer=new SandboxFiles(tools);
    private void execution(String status,String code) {
        var execution=new ToolExecutionResponse(); execution.setId(executionId); execution.setStatus(status);
        execution.setResult(Map.of("sha256","a".repeat(64),"changed",true)); execution.setFailureCode(code); execution.setFailureReason("重新读取");
        when(tools.execute(any(),anyString(),anyMap(),any())).thenReturn(execution);
    }
    @Test void newFileUsesCreateModeWithoutReadingCurrentHash() {
        execution("SUCCEEDED",null);
        assertThat(writer.writeFile(id,".gitignore","x")).containsEntry("ok",true);
        var request=org.mockito.ArgumentCaptor.forClass(Map.class); verify(tools).execute(eq(id),anyString(),request.capture(),any());
        assertThat(request.getValue()).containsEntry("mode","create");
        verify(tools,times(1)).execute(any(),anyString(),anyMap(),any());
    }
    @Test void replacementPassesObservedVersionAndMode() {
        execution("SUCCEEDED",null); writer.replaceFile(id,"x","b".repeat(64),"new");
        var request=org.mockito.ArgumentCaptor.forClass(Map.class); verify(tools).execute(eq(id),anyString(),request.capture(),any());
        assertThat(request.getValue()).containsEntry("mode","replace").containsEntry("expectedHash","b".repeat(64));
    }
    @Test void writeConflictAndEditAmbiguityRemainModelRepairable() {
        execution("FAILED","FILE_ALREADY_EXISTS");
        assertThat(writer.writeFile(id,".gitignore","x").get("errorCode")).isEqualTo("FILE_ALREADY_EXISTS");
        execution("FAILED","EDIT_TEXT_AMBIGUOUS");
        assertThat(writer.editFile(id,"x","a".repeat(64),"old","new",false).get("errorCode")).isEqualTo("EDIT_TEXT_AMBIGUOUS");
    }
    @Test void oversizedWriteIsRejectedBeforeSandboxSubmission() {
        assertThat(writer.writeFile(id,"x","x".repeat(256*1024+1))).containsEntry("ok",false); verifyNoInteractions(tools);
    }
    @Test void unavailableSandboxRemainsInfrastructureFailure() {
        when(tools.execute(any(),anyString(),anyMap(),any())).thenThrow(new IllegalStateException("Sandbox 不可用"));
        assertThat(writer.patchFile(id,"x","a".repeat(64),"patch")).containsEntry("ok",false).containsEntry("errorCode","TOOL_FAILED");
    }
}
