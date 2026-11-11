package qg.qgent.project.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class ProjectCreateRequest {
    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 16000)
    private String description;

    @Size(max = 100)
    @ArraySchema(schema = @Schema(type = "string"))
    private List<@NotNull Long> memberIds;
}
