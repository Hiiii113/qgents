package qg.qgent.workspace.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.service.WorkspaceCommands;
import qg.qgent.workspace.service.WorkspaceSelection;
import qg.qgent.workspace.service.WorkspaceService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 工作区选择与命令工具；Harness 提供当前对话的状态和按需环境获取。 */
public final class WorkspaceTools {
    private final UUID projectId;
    private final WorkspaceService workspaces;
    private final WorkspaceCommands workspaceCommands;
    private final WorkspaceSelection selection;
    private final Supplier<UUID> environment;
    private final Supplier<UUID> initializedEnvironment;
    private final Consumer<UUID> selectionChanged;

    public WorkspaceTools(UUID projectId, WorkspaceService workspaces, WorkspaceCommands commands,
                          WorkspaceSelection selection, Supplier<UUID> environment,
                          Supplier<UUID> initializedEnvironment, Consumer<UUID> selectionChanged) {
        this.projectId = projectId;
        this.workspaces = workspaces;
        this.workspaceCommands = commands;
        this.selection = selection;
        this.environment = environment;
        this.initializedEnvironment = initializedEnvironment;
        this.selectionChanged = selectionChanged;
    }

    @ParallelTool
    @Tool(name = "list_workspaces", description = "列出当前项目可选择的独立工作区，不启动沙箱。")
    public Map<String, Object> listWorkspaces() {
        return Map.of("ok", true, "workspaces", workspaces.list(projectId).stream()
                .map(workspace -> Map.of("id", workspace.getId().toString(), "status", workspace.getStatus(),
                        "updatedAt", workspace.getUpdatedAt() == null ? "" : workspace.getUpdatedAt().toString())).toList());
    }

    @Tool(name = "create_workspace", description = "创建并选择一个独立空 Workspace；不会启动沙箱。")
    public Map<String, Object> createWorkspace() {
        WorkspaceEntity workspace = workspaces.create(projectId);
        selection.select(workspace.getId());
        selectionChanged.accept(workspace.getId());
        return Map.of("ok", true, "workspaceId", workspace.getId().toString());
    }

    @Tool(name = "use_workspace", description = "选择当前项目已有 Workspace，后续代码工具使用该工作区；不会启动沙箱。")
    public Map<String, Object> useWorkspace(@ToolParam(description = "Workspace UUID") String workspaceId) {
        UUID id = workspaces.require(projectId, requiredResourceId(workspaceId)).getId();
        selection.select(id);
        selectionChanged.accept(id);
        return Map.of("ok", true, "workspaceId", id.toString());
    }

    @Tool(name = "set_working_directory", description = "设置当前 Agent 在已选 Workspace 中的工作目录，路径相对 Workspace 根；传 . 返回根目录。每个 Agent 的目录独立保存。")
    public Map<String, Object> setWorkingDirectory(
            @ToolParam(description = "相对 Workspace 根目录的路径；. 表示根目录") String directory) {
        String resolved = WorkspaceSelection.resolveDirectory(".", directory);
        selection.setDirectory(resolved);
        return Map.of("ok", true, "directory", resolved);
    }

    @Tool(name = "run_command", description = "在当前目录的 Docker 沙箱中执行 argv 数组。短等待后返回句柄；running=true 时用 command_output 继续等待。")
    public Object runCommand(@ToolParam(description = "可执行文件和参数，例如 [git,status]") List<String> command,
            @ToolParam(description = "相对当前目录的工作目录，可选") String directory,
            @ToolParam(description = "执行超时秒数，1～3600，默认 600") Integer timeoutSeconds,
            @ToolParam(description = "本次等待秒数，0～30，默认 10") Integer waitSeconds) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("命令不能为空");
        String cwd = WorkspaceSelection.resolveDirectory(selection.directory(), directory);
        return workspaceCommands.start(environment.get(), cwd, command,
                bounded(timeoutSeconds, 600, 1, 3600), bounded(waitSeconds, 10, 0, 30));
    }

    @ParallelTool
    @Tool(name = "command_output", description = "读取命令的有界输出，可短暂等待；running=true 时使用返回的游标继续读取。")
    public Object commandOutput(@ToolParam(description = "run_command 返回的执行 UUID") String executionId,
            @ToolParam(description = "stdout 字节游标，默认 0") Long stdoutOffset,
            @ToolParam(description = "stderr 字节游标，默认 0") Long stderrOffset,
            @ToolParam(description = "每个流最多读取字节，4～64000，默认 16000") Integer maxBytes,
            @ToolParam(description = "等待秒数，0～30，默认 10") Integer waitSeconds) {
        return workspaceCommands.output(initializedEnvironment.get(), requiredResourceId(executionId),
                offset(stdoutOffset), offset(stderrOffset), bounded(maxBytes, 16000, 4, 64000),
                bounded(waitSeconds, 10, 0, 30));
    }

    @Tool(name = "cancel_command", description = "停止当前 Workspace 中正在运行的命令。")
    public Object cancelCommand(@ToolParam(description = "命令执行 UUID") String executionId) {
        return workspaceCommands.cancel(initializedEnvironment.get(), requiredResourceId(executionId));
    }

    private UUID requiredResourceId(String value) {
        try { return UUID.fromString(value == null ? "" : value); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("资源 UUID 无效"); }
    }

    private int bounded(Integer value, int fallback, int min, int max) {
        int result = value == null ? fallback : value;
        if (result < min || result > max) throw new IllegalArgumentException("参数超出范围：" + min + "～" + max);
        return result;
    }

    private long offset(Long value) {
        if (value != null && value < 0) throw new IllegalArgumentException("输出游标不能小于 0");
        return value == null ? 0 : value;
    }
}
