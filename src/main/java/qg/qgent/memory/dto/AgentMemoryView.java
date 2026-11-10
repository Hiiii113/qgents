package qg.qgent.memory.dto;

/**
 * 当前 Agent 的私有记忆视图。
 * <p>
 * content 是当前群可改写的部分；inherited 是来源群成员覆盖当前群成员、只能读取的部分。
 */
public record AgentMemoryView(String content, String inherited) {

    /** 注入 Agent 上下文的全文：只读的继承部分在前，当前群部分在后。 */
    public String merged() {
        if (inherited.isBlank()) {
            return content;
        }
        return content.isBlank() ? inherited : inherited + "\n\n" + content;
    }
}
