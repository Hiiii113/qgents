package qg.qgent.workspace.service;

import qg.qgent.workspace.dto.WorkspaceFileReadResult;

import java.util.List;
import java.util.UUID;

/** 工作区代码读取：文件列表、文本与哈希、关键字检索。按 Worker 开关选择本地或远程实现。 */
public interface WorkspaceCodeAccess {

    /**
     * 列出 Workspace 中所有代码文件（相对路径，已排除 .git、target 等目录）。
     *
     * @param workspaceId 目标 Workspace。
     * @return 排序后的相对路径列表；目录不存在或不可用时返回空列表。
     */
    List<String> listFiles(UUID workspaceId);

    /**
     * 读取单个文件的文本内容及其原始字节 SHA-256。
     *
     * @param workspaceId 目标 Workspace。
     * @param path        相对路径。
     * @return 成功时 ok=true 并给出 UTF-8 文本内容与原始字节 SHA-256；路径越界、文件
     *         不存在或读取失败时 ok=false 并给出错误说明（实现不得返回 null）。
     */
    WorkspaceFileReadResult readFile(UUID workspaceId, String path);

    /**
     * 在 Workspace 代码中检索包含指定关键字（忽略大小写）的文件。
     *
     * @param workspaceId 目标 Workspace。
     * @param query       检索关键字。
     * @return 命中的相对路径列表；目录不可用或查询为空时返回空列表。
     */
    List<String> searchCode(UUID workspaceId, String query);
}
