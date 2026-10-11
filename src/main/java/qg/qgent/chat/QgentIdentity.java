package qg.qgent.chat;

import java.util.UUID;

/** 固定聊天助手的提及标识，不对应可管理的 Agent 记录。 */
public final class QgentIdentity {
    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final String NAME = "Qgent";
    private QgentIdentity() {}
}
