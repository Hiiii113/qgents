package qg.qgent.workspace.service;

import java.util.List;
import java.util.Map;

/** Sandbox 命令执行及其句柄控制；凭证环境只由附加能力注入。 */
public interface WorkspaceCommands {
    Map<String, Object> run(Long workspaceId, String directory, List<String> command, Map<String, String> environment);
    Map<String, Object> start(Long workspaceId, String directory, List<String> command, int timeoutSeconds, int waitSeconds);
    Map<String, Object> output(Long workspaceId, Long executionId, long stdoutOffset, long stderrOffset, int maxBytes, int waitSeconds);
    Map<String, Object> cancel(Long workspaceId, Long executionId);
}
