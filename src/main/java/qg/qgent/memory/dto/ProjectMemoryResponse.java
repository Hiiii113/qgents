package qg.qgent.memory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 项目长期记忆 memory.md 的内容、内容版本和最后修改时间。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMemoryResponse {

    @Schema(description = "所属项目 ID")
    private String projectId;

    @Schema(description = "memory.md 全文")
    private String content;

    @Schema(description = "内容版本（正文 SHA-256），保存时用于检测并发修改")
    private String version;

    @Schema(description = "最后修改时间（UTC）")
    private LocalDateTime updatedAt;
}
