package qg.qgent.memory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 编辑草稿或审核中内容，null 字段表示不修改。 */
@Data
public class MemoryUpdateRequest {

    @Size(max = 255)
    @Schema(description = "新标题，null 表示不修改", maxLength = 255)
    private String title;

    @Schema(description = "新正文，null 表示不修改")
    private String content;

    @Size(max = 64)
    @Schema(description = "新分类，null 表示不修改", maxLength = 64)
    private String category;

    @Schema(description = "新标签列表，null 表示不修改")
    private List<String> tags;
}
