package qg.qgent.workspace.service;

import qg.qgent.workspace.dto.WorkspaceDirectoryResult;
import qg.qgent.workspace.dto.WorkspaceWriteResult;

import java.util.UUID;

/** 工作区文件修改接口，通过 Worker 在沙箱工作区中执行。路径均相对于工作区根目录。 */
public interface WorkspaceCodeWriter {

    /** 递归创建目录；已存在时返回成功且 changed=false。 */
    WorkspaceDirectoryResult createDirectory(UUID workspaceId, String path);

    /** 写入 UTF-8 文件，自动创建父目录；允许覆盖已有内容。 */
    WorkspaceWriteResult writeFile(UUID workspaceId, String path, String content);

    /** 原子替换已有 UTF-8 文件；expectedHash 为读取时得到的 SHA-256，文件变化后需重新读取。 */
    WorkspaceWriteResult replaceFile(UUID workspaceId, String path, String expectedHash, String content);

    /** 校验 expectedHash 后应用 unified diff；哈希或补丁不匹配时不写入。 */
    WorkspaceWriteResult patchFile(UUID workspaceId, String path, String expectedHash, String patch);

    /** 校验 expectedHash 后补全末尾换行，沿用文件的 LF/CRLF 风格；无需修改时 changed=false。 */
    WorkspaceWriteResult ensureTrailingNewline(UUID workspaceId, String path, String expectedHash);
}
