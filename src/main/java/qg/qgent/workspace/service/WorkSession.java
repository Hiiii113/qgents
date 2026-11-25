package qg.qgent.workspace.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个 Agent 对话的工作区会话：记录当前 Workspace 与相对目录，并按需准备沙箱环境。
 * <p>
 * 工具只通过这个接口使用工作区；沙箱懒启动、取消拦截与跨子 Agent 复用由 Harness 实现（WorkspaceSession）。
 */
public interface WorkSession {

    /**
     * 当前选中的 Workspace；尚未选择时为 null。
     */
    Long workspaceId();

    /**
     * 当前目录，相对 Workspace 根。
     */
    String directory();

    /**
     * 用当前选择准备代码环境；未选择时使用项目默认 Workspace。
     */
    Long ensure();

    /**
     * 准备指定 Workspace 的代码环境，不改变当前选择。
     */
    Long ensure(Long workspaceId);

    /**
     * 当前选择已初始化时返回它，否则提示先初始化。
     */
    Long requireInitialized();

    /**
     * 切换当前选择并重置目录。
     */
    void select(Long workspaceId);

    void setDirectory(String directory);

    /**
     * 将输入路径解析为相对 Workspace 根的规范路径，拒绝绝对路径与越出根目录的 {@code ..}。
     */
    static String resolveDirectory(String base, String directory) {
        String input =
            directory == null || directory.isBlank()
                ? "."
                : directory.trim().replace('\\', '/');
        if (input.startsWith("/") || input.matches("^[A-Za-z]:/.*") || input.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("工作目录必须位于 Workspace 内");
        }
        List<String> parts = new ArrayList<>();
        for (String item : (base == null ? "." : base).split("/")) {
            if (!item.isBlank() && !".".equals(item)) parts.add(item);
        }
        for (String item : input.split("/")) {
            if (item.isBlank() || ".".equals(item)) continue;
            if ("..".equals(item)) {
                if (parts.isEmpty()) throw new IllegalArgumentException("工作目录必须位于 Workspace 内");
                parts.remove(parts.size() - 1);
            } else {
                parts.add(item);
            }
        }
        return parts.isEmpty() ? "." : String.join("/", parts);
    }
}
