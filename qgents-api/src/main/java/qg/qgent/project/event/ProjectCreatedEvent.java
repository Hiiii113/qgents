package qg.qgent.project.event;

import java.util.UUID;


public record ProjectCreatedEvent(UUID projectId, String projectName, UUID creatorUserId) {

}
