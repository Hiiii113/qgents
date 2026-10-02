package qg.qgent.workspace.service;

import qg.qgent.workspace.dto.WorkspaceWriteResult;
import java.util.UUID;

/** 文件写入由 Worker 执行；创建和替换分别检查目标存在性。 */
public interface WorkspaceCodeWriter {
    WorkspaceWriteResult writeFile(UUID workspaceId, String path, String content);
    WorkspaceWriteResult replaceFile(UUID workspaceId, String path, String expectedHash, String content);
    WorkspaceWriteResult patchFile(UUID workspaceId, String path, String expectedHash, String patch);
    WorkspaceWriteResult editFile(UUID workspaceId, String path, String expectedHash, String oldText, String newText, boolean replaceAll);
}
