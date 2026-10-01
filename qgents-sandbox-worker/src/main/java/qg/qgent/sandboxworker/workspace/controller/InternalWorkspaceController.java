package qg.qgent.sandboxworker.workspace.controller;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import qg.qgent.sandboxworker.workspace.service.WorkspaceManagerService;
import qg.qgent.sandboxworker.workspace.dto.*;
import java.util.UUID;
@RestController
@RequestMapping("/internal/v1/workspaces/{workspaceId}")
@RequiredArgsConstructor
public class InternalWorkspaceController {
    private final WorkspaceManagerService workspaces;
    @PutMapping public WorkspaceResponse provision(@PathVariable UUID workspaceId,@Valid @RequestBody WorkspaceProvisionRequest request) { return workspaces.provision(workspaceId,request); }
    @GetMapping public WorkspaceResponse get(@PathVariable UUID workspaceId) { return workspaces.get(workspaceId); }
    @DeleteMapping public void delete(@PathVariable UUID workspaceId) { workspaces.delete(workspaceId); }
}
