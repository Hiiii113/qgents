package qg.qgent.workspace.service;
import java.util.*;
/** 在 Task 工作区内执行参数数组命令。凭证环境仅由能力工具注入。 */
public interface WorkspaceCommands {
    Map<String, Object> run(UUID workspaceId, String directory, List<String> command, Map<String, String> environment);
}
