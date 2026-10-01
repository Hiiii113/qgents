package qg.qgent.sandboxworker.workspace.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.sandboxworker.common.web.WorkerException;
import qg.qgent.sandboxworker.sandbox.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.sandbox.service.SandboxService;
import qg.qgent.sandboxworker.workspace.dto.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.UUID;

/** 只管理持久工作目录；目录里的仓库与分支由 Agent 操作。 */
@Service
@RequiredArgsConstructor
public class WorkspaceManagerService {
    private final SandboxWorkerProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final WorkspaceOperationLock lock;
    private final SandboxService sandboxes;

    public WorkspaceResponse provision(UUID id, WorkspaceProvisionRequest request) {
        return lock.execute(storageKey(id), () -> {
            Path metadata = metadata(id);
            if (Files.exists(metadata)) {
                WorkspaceResponse existing = get(id);
                if (!existing.getProjectId().equals(request.getProjectId()))
                    throw new WorkerException(HttpStatus.CONFLICT, "WORKSPACE_SPEC_CONFLICT", "工作区属于其他项目");
                return existing;
            }
            try {
                Files.createDirectories(directory(id));
                Files.createDirectories(metadata.getParent());
                String now = clock.instant().toString();
                WorkspaceResponse response = new WorkspaceResponse(id, request.getProjectId(), storageKey(id), "READY", now, now);
                json.writeValue(metadata.toFile(), response);
                return response;
            } catch (Exception e) {
                throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "WORKSPACE_PROVISION_FAILED", "准备工作区失败");
            }
        });
    }
    public WorkspaceResponse get(UUID id) {
        try { return json.readValue(metadata(id).toFile(), WorkspaceResponse.class); }
        catch (Exception e) { throw new WorkerException(HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND", "工作区不存在"); }
    }
    public void delete(UUID id) {
        lock.execute(storageKey(id), () -> {
            if (sandboxes.isWorkspaceInUse(storageKey(id)))
                throw new WorkerException(HttpStatus.CONFLICT, "WORKSPACE_IN_USE", "工作区仍在使用");
            Path directory = directory(id);
            try {
                if (Files.exists(directory)) {
                    try (var entries = Files.walk(directory)) {
                        for (Path path : entries.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                    }
                }
                Files.deleteIfExists(metadata(id));
            } catch (Exception e) { throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "WORKSPACE_DELETE_FAILED", "删除工作区失败"); }
            return null;
        });
    }
    private String storageKey(UUID id) { return "workspaces/" + id; }
    private Path directory(UUID id) { return Path.of(properties.getWorkspaceLocalRoot()).resolve(id.toString()); }
    private Path metadata(UUID id) { return Path.of(properties.getWorkspaceLocalRoot()).resolve(".metadata").resolve(id + ".json"); }
}
