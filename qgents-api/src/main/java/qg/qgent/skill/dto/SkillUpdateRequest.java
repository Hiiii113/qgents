package qg.qgent.skill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 修改 Skill 内容，未传字段不修改。 */
@Data
public class SkillUpdateRequest {

    @Size(max = 255)
    @Schema(description = "新名称，null 表示不修改", maxLength = 255)
    private String name;

    @Schema(description = "新正文，null 表示不修改")
    private String content;

    @Schema(description = "新标签列表，null 表示不修改")
    private List<String> tags;
}
