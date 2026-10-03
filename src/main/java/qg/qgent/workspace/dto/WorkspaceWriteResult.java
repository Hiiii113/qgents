package qg.qgent.workspace.dto;

import lombok.Data;

/** 文件修改结果：成功时返回路径、新哈希和 changed；失败时返回失败码、原因及是否为基础设施错误。 */
@Data
public class WorkspaceWriteResult {

    // 是否写入成功。
    private boolean ok;
    // 相对路径；仅成功时有意义。
    private String path;
    // 失败原因（不得包含宿主机绝对路径或 Secret）。
    private String error;
    // Sandbox 返回的失败码，用于区分工具错误与服务不可用。
    private String failureCode;
    // 是否为基础设施级失败（workspace 不可用 / 文件系统错误）；仅 ok=false 时有意义。
    private boolean infrastructureFailure;
    // 写入后文件内容的 SHA-256（小写 hex）；仅 ok=true 时有意义，由 Sandbox 返回。
    private String newSha256;
    // 本次写入是否实际改变了文件内容；仅 ok=true 时有意义。
    private boolean changed;

    /** 成功工厂：写入后的新 SHA-256 与是否实际变化。 */
    public static WorkspaceWriteResult ok(String path, String newSha256, boolean changed) {
        WorkspaceWriteResult result = new WorkspaceWriteResult();
        result.setOk(true);
        result.setPath(path);
        result.setNewSha256(newSha256);
        result.setChanged(changed);
        return result;
    }

    /** 工具级失败：模型可依据错误信息自行纠正，不判基础设施失败。 */
    public static WorkspaceWriteResult fail(String path, String error) {
        return fail(path, null, error);
    }

    /** 带稳定失败码的工具级失败。 */
    public static WorkspaceWriteResult fail(String path, String failureCode, String error) {
        WorkspaceWriteResult result = new WorkspaceWriteResult();
        result.setOk(false);
        result.setPath(path);
        result.setFailureCode(failureCode);
        result.setError(error);
        return result;
    }

    /** 基础设施级失败：workspace 不可用、文件系统错误等，经工具 loop 返回失败信息。 */
    public static WorkspaceWriteResult infraFail(String path, String error) {
        return infraFail(path, null, error);
    }

    /** 带稳定失败码的基础设施失败。 */
    public static WorkspaceWriteResult infraFail(String path, String failureCode, String error) {
        WorkspaceWriteResult result = fail(path, failureCode, error);
        result.setInfrastructureFailure(true);
        return result;
    }
}
