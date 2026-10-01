package qg.qgent.sandboxworker.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 真实创建的 Git Commit。
 */
@Data
@AllArgsConstructor
@Schema(description = "真实创建的 Git Commit")
public class GitCommitResponse {
    /**
     * 真实 Commit SHA。
     */
    @Schema(description = "真实 Commit SHA")
    private String commitSha;
}
