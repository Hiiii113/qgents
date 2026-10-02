package qg.qgent.skill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 创建 Skill 草稿请求。 */
@Data
public class SkillCreateRequest {

    @NotBlank
    @Size(max = 255)
    @Schema(description = "Skill 名称（必填）", maxLength = 255, requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @NotBlank
    @Schema(description = "可复用操作规范正文（必填）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;

    @Schema(description = "标签列表")
    private List<String> tags;

    @Size(max = 32)
    @Schema(description = "可见性：PRIVATE / PROJECT_SHARED，默认 PRIVATE", maxLength = 32)
    private String visibility;
}
