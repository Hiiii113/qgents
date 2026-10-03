package qg.qgent.agent.tool;

import java.util.ArrayList;
import java.util.List;

/** 工作区相对路径归一化，实际目标由文件访问端检查。 */
public final class WorkspacePathPolicy {

    private WorkspacePathPolicy() { }

    /** 归一化相对路径；绝对路径、父目录跳转及空字符返回 null。 */
    public static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String value = path.trim().replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:/.*")) {
            return null;
        }
        String[] segments = value.split("/", -1);
        List<String> safe = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment) || segment.indexOf('\0') >= 0) {
                return null;
            }
            safe.add(segment);
        }
        return String.join("/", safe);
    }
}
