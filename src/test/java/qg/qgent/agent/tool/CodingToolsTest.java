package qg.qgent.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import qg.qgent.workspace.service.WorkspaceCodeAccess;
import qg.qgent.workspace.service.WorkspaceCodeWriter;
import qg.qgent.workspace.dto.WorkspaceWriteResult;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodingToolsTest {
    private final UUID id = UUID.randomUUID();
    private final WorkspaceCodeAccess code = mock(WorkspaceCodeAccess.class);
    private final WorkspaceCodeWriter writer = mock(WorkspaceCodeWriter.class);
    private final CodingTools tools = new CodingTools(() -> id, code, writer);
    private final String hash = "a".repeat(64);
    private void read(String path) {
        when(code.readFile(id, path, 1, 200)).thenReturn(Map.of("ok", true, "sha256", hash));
        tools.readFile(path, null, null);
    }
    @Test void modelSchemaUsesWindowsAndHidesInternalHashesAndOldTools() {
        var definitions = java.util.Arrays.stream(ToolCallbacks.from(tools)).map(t -> t.getToolDefinition()).toList();
        assertThat(definitions).extracting(d -> d.name()).containsExactlyInAnyOrder("read_file", "glob_files", "search_code", "write_file", "edit_file", "apply_patch");
        assertThat(definitions).allSatisfy(d -> assertThat(d.inputSchema()).doesNotContain("expectedHash"));
        assertThat(definitions.stream().filter(d -> d.name().equals("read_file")).findFirst().orElseThrow().inputSchema()).contains("startLine", "lineCount");
    }
    @Test void parallelMetadataPreservesSchemaAndLeavesWritesExclusive() {
        var nativeCallbacks = ToolCallbacks.from(tools);
        var classified = ParallelToolCallback.from(tools);
        for (var callback : classified) {
            var original = java.util.Arrays.stream(nativeCallbacks)
                    .filter(c -> c.getToolDefinition().name().equals(callback.getToolDefinition().name())).findFirst().orElseThrow();
            assertThat(callback.getToolDefinition()).isEqualTo(original.getToolDefinition());
            boolean read = java.util.Set.of("read_file", "glob_files", "search_code").contains(callback.getToolDefinition().name());
            assertThat(callback instanceof ParallelToolCallback).isEqualTo(read);
        }
    }
    @Test void readWindowDoesNotReadMorePages() {
        var result = Map.<String,Object>of("ok", true, "sha256", hash, "truncated", true, "nextLine", 501);
        when(code.readFile(id, "big.java", 301, 200)).thenReturn(result);
        assertThat(tools.readFile("big.java", 301, 200)).isSameAs(result);
        verify(code).readFile(id, "big.java", 301, 200); verifyNoMoreInteractions(code);
    }
    @Test void searchFailureDoesNotBecomeEmptySuccess() {
        var error = Map.<String,Object>of("ok",false,"errorCode","PROCESS_EXIT_NONZERO","error","invalid regex");
        when(code.searchCode(id,"[","src","**/*.java",100)).thenReturn(error);
        assertThat(tools.searchCode("[","src","**/*.java",null)).isSameAs(error);
    }
    @Test void unreadHiddenFileIsCreatedAtSandboxWithoutListingRepository() {
        when(writer.writeFile(id,".gitignore","new")).thenReturn(WorkspaceWriteResult.fail(".gitignore","FILE_ALREADY_EXISTS","已有文件"));
        assertThat(tools.writeFile("./.gitignore","new")).containsEntry("ok",false).containsEntry("errorCode","FILE_ALREADY_EXISTS");
        verifyNoInteractions(code); verify(writer).writeFile(id,".gitignore","new");
    }
    @Test void observedFileUsesCachedVersionForWholeFileReplacement() {
        read("x.txt");
        when(writer.replaceFile(id,"x.txt",hash,"next")).thenReturn(WorkspaceWriteResult.ok("x.txt","b".repeat(64),true));
        assertThat(tools.writeFile("x.txt","next")).containsEntry("ok",true).containsEntry("sha256","b".repeat(64));
        verify(writer).replaceFile(id,"x.txt",hash,"next");
    }
    @Test void editRequiresReadAndPreservesConflictCode() {
        assertThat(tools.editFile("x.txt","old","",null)).containsEntry("errorCode","FILE_NOT_READ");
        verifyNoInteractions(writer);
        read("x.txt");
        when(writer.editFile(id,"x.txt",hash,"old","",false)).thenReturn(WorkspaceWriteResult.fail("x.txt","FILE_HASH_MISMATCH","重新读取"));
        assertThat(tools.editFile("x.txt","old","",false)).containsEntry("errorCode","FILE_HASH_MISMATCH");
    }
    @Test void switchingWorkspaceInvalidatesObservedVersions() {
        var selected = new AtomicReference<>(id);
        var tool = new CodingTools(selected::get,code,writer);
        when(code.readFile(id,"x.txt",1,200)).thenReturn(Map.of("ok",true,"sha256",hash));
        tool.readFile("x.txt",null,null); selected.set(UUID.randomUUID());
        assertThat(tool.applyPatch("x.txt","patch")).containsEntry("errorCode","FILE_NOT_READ");
        verifyNoInteractions(writer);
    }
    @Test void successfulEditUpdatesVersionForNextMutation() {
        read("x.txt");
        when(writer.editFile(id,"x.txt",hash,"old","new",false)).thenReturn(WorkspaceWriteResult.ok("x.txt","b".repeat(64),true));
        tools.editFile("x.txt","old","new",null);
        when(writer.patchFile(id,"x.txt","b".repeat(64),"patch")).thenReturn(WorkspaceWriteResult.ok("x.txt","c".repeat(64),true));
        assertThat(tools.applyPatch("x.txt","patch")).containsEntry("sha256","c".repeat(64));
    }
    @Test void invalidArgumentsDoNotExecuteTools() {
        assertThatThrownBy(() -> tools.readFile("../x",null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.readFile("x",1,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.globFiles(null,null,1001)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(code,writer);
    }
}
