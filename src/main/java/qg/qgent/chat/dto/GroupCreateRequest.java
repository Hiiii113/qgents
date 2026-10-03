package qg.qgent.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;
import java.util.UUID;

/** 创建群时可同时邀请项目成员及可用 Agent。创建者自动加入。 */
@Data
public class GroupCreateRequest {
    @NotBlank @Size(max = 255)
    private String title;
    @Size(max = 2000)
    private String description;
    @Size(max = 100)
    private List<UUID> userIds = List.of();
    @Size(max = 100)
    private List<UUID> agentIds = List.of();
}
