package qg.qgent.workspace.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.service.SandboxService;
import qg.qgent.workspace.dto.*;
import java.nio.file.*;
import java.time.Clock;

/** 只管理持久工作目录；目录里的仓库与分支由 Agent 操作。 */
@Service
@RequiredArgsConstructor
public class WorkspaceStorageService {
    private final SandboxProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final WorkspaceOperationLock lock;
    private final SandboxService sandboxes;

    public WorkspaceResponse provision(Long id, String storageKey, WorkspaceProvisionRequest request) {
        return lock.execute(storageKey, () -> {
            Path metadata = metadata(storageKey);
            if (Files.exists(metadata)) {
                WorkspaceResponse existing = get(storageKey);
                if (!existing.getProjectId().equals(request.getProjectId()))
                    throw new SandboxException(HttpStatus.CONFLICT, "WORKSPACE_SPEC_CONFLICT", "工作区属于其他项目");
                return existing;
            }
            try {
                Files.createDirectories(directory(storageKey));
                Files.createDirectories(metadata.getParent());
                String now = clock.instant().toString();
                WorkspaceResponse response = new WorkspaceResponse(id, request.getProjectId(), storageKey, "READY", now, now);
                json.writeValue(metadata.toFile(), response);
                return response;
            } catch (Exception e) {
                throw new SandboxException(HttpStatus.INTERNAL_SERVER_ERROR, "WORKSPACE_PROVISION_FAILED", "准备工作区失败");
            }
        });
    }
    public WorkspaceResponse get(String storageKey) {
        try { return json.readValue(metadata(storageKey).toFile(), WorkspaceResponse.class); }
        catch (Exception e) { throw new SandboxException(HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND", "工作区不存在"); }
    }
    public void delete(String storageKey) {
        lock.execute(storageKey, () -> {
            if (sandboxes.isWorkspaceInUse(storageKey))
                throw new SandboxException(HttpStatus.CONFLICT, "WORKSPACE_IN_USE", "工作区仍在使用");
            Path directory = directory(storageKey);
            try {
                if (Files.exists(directory)) {
                    try (var entries = Files.walk(directory)) {
                        for (Path path : entries.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                    }
                }
                Files.deleteIfExists(metadata(storageKey));
            } catch (Exception e) { throw new SandboxException(HttpStatus.INTERNAL_SERVER_ERROR, "WORKSPACE_DELETE_FAILED", "删除工作区失败"); }
            return null;
        });
    }
    private Path directory(String storageKey) {
        return Path.of(properties.getWorkspaceLocalRoot()).resolve(suffix(storageKey));
    }
    private Path metadata(String storageKey) {
        return Path.of(properties.getWorkspaceLocalRoot()).resolve(".metadata").resolve(suffix(storageKey) + ".json");
    }
    private String suffix(String storageKey) { return storageKey.substring("workspaces/".length()); }
}
