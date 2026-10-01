package qg.qgent.agent.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 工作区相对路径检查；可选路径列表用于限制文件操作范围。 */
public final class WorkspacePathPolicy {

    private final List<String> allowedPaths;

    private WorkspacePathPolicy(Collection<String> paths) {
        Set<String> normalized = new LinkedHashSet<>();
        if (paths != null) {
            for (String path : paths) {
                String value = normalize(path);
                if (value != null) {
                    normalized.add(value);
                }
            }
        }
        this.allowedPaths = List.copyOf(normalized);
    }

    /** 空列表表示整个工作区可用。 */
    public static WorkspacePathPolicy of(Collection<String> paths) {
        return new WorkspacePathPolicy(paths);
    }

    public boolean isUnrestricted() {
        return allowedPaths.isEmpty();
    }

    /**
     * Exact paths and descendants of declared directory/repository paths are
     * accepted. The writer still validates the real filesystem target, so a
     * file accidentally used as a directory cannot be bypassed.
     */
    public boolean allows(String path) {
        String candidate = normalize(path);
        if (candidate == null) {
            return false;
        }
        if (isUnrestricted()) {
            return true;
        }
        for (String allowed : allowedPaths) {
            if (allowed.isEmpty() || candidate.equals(allowed) || candidate.startsWith(allowed + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Allows a directory that is an ancestor of a declared file path. */
    public boolean allowsDirectory(String path) {
        String candidate = normalize(path);
        if (candidate == null) {
            return false;
        }
        if (isUnrestricted()) {
            return true;
        }
        for (String allowed : allowedPaths) {
            if (allowed.isEmpty() || candidate.equals(allowed) || candidate.startsWith(allowed + "/")
                    || allowed.startsWith(candidate + "/")) {
                return true;
            }
        }
        return false;
    }

    public List<String> allowedPaths() {
        return allowedPaths;
    }

    /**
     * Normalizes a workspace-relative path and rejects absolute paths and any
     * parent traversal segment. A null return means the input is unsafe.
     */
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
