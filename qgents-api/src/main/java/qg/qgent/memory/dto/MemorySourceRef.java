package qg.qgent.memory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/** Memory 来源消息引用。 */
@Data
public class MemorySourceRef {

    @NotNull
    @Schema(description = "消息所属项目群聊 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID groupId;

    @NotNull
    @Schema(description = "消息 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID messageId;
}
