package qg.qgent.chat.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;
import java.util.UUID;

/** 向已有群聊追加成员，不替换已加入的成员。 */
@Data
public class GroupInviteRequest {
    @Size(max = 100)
    private List<UUID> userIds = List.of();
    @Size(max = 100)
    private List<UUID> agentIds = List.of();
}
