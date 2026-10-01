package qg.qgent.sandbox.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 控制层申请创建 Sandbox 的请求（镜像 Worker 的 CreateSandboxRequest）。
 * 只包含资源标识与受控配置，不携带宿主机路径、Docker 参数或凭证。
 */
@Data
public class WorkerCreateSandboxRequest {

    /**
     * 由控制层生成的 Sandbox 唯一编号；重复编号会被 Worker 拒绝。
     */
    private UUID sandboxId;

    /**
     * Sandbox 所属 Task 编号，用于 Worker 生命周期和工具执行日志关联。
     */
    private UUID taskId;

    /**
     * Workspace 不透明存储键，由 Worker 解析为受控根目录下的实际路径。
     */
    private String workspaceStorageKey;

    /**
     * Worker 白名单中的镜像配置名，例如 dev-tools。
     */
    private String imageProfile;

    /**
     * 可选资源限制。
     */
    private WorkerResourceLimits limits;

    /**
     * 项目仓库编号列表，工具执行时据此解析工作目录。
     */
    private List<UUID> repositoryIds;
}
