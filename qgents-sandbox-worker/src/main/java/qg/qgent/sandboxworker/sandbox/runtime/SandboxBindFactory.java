package qg.qgent.sandboxworker.sandbox.runtime;
import com.github.dockerjava.api.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.workspace.service.WorkspacePathResolver;
import java.util.List;
/** 完整工作区挂载；Agent 可在其中 clone 仓库并使用 Git。 */
@Component
@RequiredArgsConstructor
public class SandboxBindFactory {
    private final WorkspacePathResolver paths;
    public List<Bind> create(SandboxAllocation allocation) {
        paths.resolveLocal(allocation.getWorkspaceStorageKey());
        return List.of(new Bind(paths.resolveDockerHost(allocation.getWorkspaceStorageKey()).toString(),
                new Volume("/workspace"), AccessMode.rw));
    }
}
