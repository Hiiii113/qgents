package qg.qgent.chat.event;

import qg.qgent.chat.dto.Mention;

import java.util.List;
import java.util.UUID;

/** 消息提交后通知被提及用户，并启动被 @ 的 Agent。 */
public record MessageSentEvent(UUID actor, UUID projectId, UUID groupId, UUID messageId,
                               List<Mention> mentions) {
}
