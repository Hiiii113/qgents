package qg.qgent.memory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 保存项目记忆：整篇正文加上读取时拿到的版本号。 */
@Data
public class ProjectMemoryUpdateRequest {

    @Schema(description = "memory.md 全文；空字符串表示清空")
    @NotNull
    private String content;

    @Schema(description = "读取时返回的内容版本，与当前内容不一致时返回 409")
    @NotNull
    private String version;
}
