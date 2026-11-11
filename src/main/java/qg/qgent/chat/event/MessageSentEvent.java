package qg.qgent.chat.event;

import java.util.List;
import qg.qgent.chat.dto.Mention;

/** 消息提交后通知被提及用户，并启动被 @ 的 Agent。 */
public record MessageSentEvent(
        Long actor, Long projectId, Long groupId, Long messageId, List<Mention> mentions) {}
