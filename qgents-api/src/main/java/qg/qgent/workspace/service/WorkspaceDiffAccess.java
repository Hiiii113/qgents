package qg.qgent.workspace.service;

import qg.qgent.workspace.dto.GitDiffResult;

import java.util.UUID;

/** 读取当前工作区的真实 Git 差异；按 Worker 开关选择本地或远程实现。 */
public interface WorkspaceDiffAccess {

    /**
     * 读取指定 Workspace 的当前修改 diff（相对 base 的工作树变更）。
     *
     * @param workspaceId 目标 Workspace。
     * @return 只读 diff 结果；diff 不可用（未就绪/失败）时 ok=false 并给出原因。
     */
    GitDiffResult diff(UUID workspaceId);
}
