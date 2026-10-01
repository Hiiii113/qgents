package qg.qgent.sandbox.service;
import org.springframework.stereotype.Service;
import qg.qgent.agent.service.AgentWorkerExecutionService;
import qg.qgent.sandbox.client.SandboxWorkerClient;
import qg.qgent.sandbox.config.SandboxWorkerProperties;
import qg.qgent.workspace.service.WorkspaceCommands;
import java.time.Duration;
import java.util.*;
@Service
public class WorkerWorkspaceCommands extends AbstractWorkerToolPort implements WorkspaceCommands {
    public WorkerWorkspaceCommands(SandboxWorkerClient client, SandboxSessionManager sessions,
                                   SandboxWorkerProperties properties, AgentWorkerExecutionService executions) {
        super(client, sessions, properties, executions);
    }
    public Map<String, Object> run(UUID id, String directory, List<String> command, Map<String, String> environment) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("命令不能为空");
        var execution = executeTool(id,"command.run", Map.of("directory", directory == null?".":directory,"command", command),
                Duration.ofMinutes(10), environment);
        if (!"SUCCEEDED".equals(execution.getStatus()) && execution.getExitCode() == null)
            return Map.of("ok", false,"error", execution.getFailureReason() == null?"命令执行失败":execution.getFailureReason());
        Map<String, Object> result = new LinkedHashMap<>(resultOf(execution));
        result.put("ok", Integer.valueOf(0).equals(execution.getExitCode()));
        result.put("exitCode", execution.getExitCode());
        return result;
    }
}
