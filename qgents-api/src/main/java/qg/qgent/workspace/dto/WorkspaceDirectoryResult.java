package qg.qgent.workspace.dto;

import lombok.Data;

/**
 * Workspace 目录创建结果。目录创建是可持久化的工作树变更，但不会伪装成 Git 文件 Diff。
 */
@Data
public class WorkspaceDirectoryResult {
    private boolean ok;
    private String path;
    private boolean created;
    private String error;
    /**
     * Worker 返回的失败码，用于区分工具错误与服务不可用。
     */
    private String failureCode;
    private boolean infrastructureFailure;

    public static WorkspaceDirectoryResult ok(String path, boolean created) {
        WorkspaceDirectoryResult result = new WorkspaceDirectoryResult();
        result.ok = true;
        result.path = path;
        result.created = created;
        return result;
    }

    public static WorkspaceDirectoryResult fail(String path, String error) {
        return fail(path, null, error);
    }

    public static WorkspaceDirectoryResult fail(String path, String failureCode, String error) {
        WorkspaceDirectoryResult result = new WorkspaceDirectoryResult();
        result.path = path;
        result.failureCode = failureCode;
        result.error = error;
        return result;
    }

    public static WorkspaceDirectoryResult infraFail(String path, String error) {
        return infraFail(path, null, error);
    }

    public static WorkspaceDirectoryResult infraFail(String path, String failureCode, String error) {
        WorkspaceDirectoryResult result = fail(path, failureCode, error);
        result.infrastructureFailure = true;
        return result;
    }
}
