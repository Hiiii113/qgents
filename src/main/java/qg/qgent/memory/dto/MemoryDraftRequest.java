package qg.qgent.memory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** AI 自动沉淀 Memory 草稿请求。 */
@Data
public class MemoryDraftRequest {

    @NotNull
    @Schema(description = "取最近聊天的项目群聊 ID（前端当前打开的群）", requiredMode = Schema.RequiredMode.REQUIRED)
    private java.util.UUID groupId;

    @Size(max = 2048)
    @Schema(description = "沉淀指令（可空），如「沉淀为项目认证安全约定」", maxLength = 2048)
    private String instruction;
}
