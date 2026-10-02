package qg.qgent.workspace.service;

import java.util.Map;
import java.util.UUID;

/** 通过 Worker 按窗口读取文件、发现路径及检索文本。 */
public interface WorkspaceCodeAccess {
    Map<String, Object> listFiles(UUID workspaceId, String path, String glob, int limit);
    Map<String, Object> readFile(UUID workspaceId, String path, int startLine, int lineCount);
    Map<String, Object> searchCode(UUID workspaceId, String pattern, String path, String glob, int limit);
}
