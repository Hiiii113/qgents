package qg.qgent.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentAssignmentListItem {


    @Schema(description = "分配类型：CHAT_GROUP")
    private String type;

    /**
     * 资源 ID（项目群聊 ID）。
     */
    @Schema(description = "资源 ID")
    private String resourceId;

    /**
     * 资源名称。
     */
    @Schema(description = "资源名称")
    private String resourceName;

    /**
     * 状态：ACTIVE / INACTIVE。
     */
    @Schema(description = "状态：ACTIVE / INACTIVE")
    private String status;
}
