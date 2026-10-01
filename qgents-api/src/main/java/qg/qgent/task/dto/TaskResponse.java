package qg.qgent.task.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Task 及其固定工作区的摘要。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskResponse {
    private String id;
    private String projectId;
    private String groupId;
    private String triggerMessageId;
    private String title;
    private String displayCode;
    private String requirement;
    private String status;
    private String workspaceId;
    private List<String> repositoryIds;
    private String createdBy;
    private String createdAt;
    private String updatedAt;
}
