package qg.qgent.memory.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.memory.dto.MemoryCreateRequest;
import qg.qgent.memory.dto.MemoryResponse;
import qg.qgent.memory.dto.MemoryUpdateRequest;
import qg.qgent.memory.service.MemoryService;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Exposes project Memory operations to one AgentRun through the current user's permissions. */
public class MemoryTools {
    private static final int DEFAULT_LIST_LIMIT = 5;
    private static final int MAX_LIST_LIMIT = 10;
    private static final int PREVIEW_CHARS = 200;
    private static final int DEFAULT_READ_CHARS = 4_000;
    private static final int MAX_READ_CHARS = 8_000;

    private final MemoryService memories;
    private final Long actor;
    private final Long projectId;

    public MemoryTools(MemoryService memories, Long actor, Long projectId) {
        this.memories = memories;
        this.actor = actor;
        this.projectId = projectId;
    }

    /** Search visible Memory using narrow summaries; use read_memory when a full content window is needed. */
    @ParallelTool
    @Tool(name = "list_memories", description = "查询当前用户可见的项目 Memory，按标题、正文、分类或标签中的关键字筛选；"
            + "可选按状态和标签缩小范围。结果只返回最多 10 条摘要，需了解完整正文时调用 read_memory。"
            + "只把确认过的项目事实作为知识维护，不要将临时推测记录为 Memory。")
    public Map<String, Object> listMemories(
            @ToolParam(description = "可选关键字，匹配标题、正文、分类或标签", required = false) String query,
            @ToolParam(description = "可选状态：DRAFT、PENDING_REVIEW、APPROVED、REJECTED、ARCHIVED", required = false) String status,
            @ToolParam(description = "可选标签", required = false) String tag,
            @ToolParam(description = "最多返回条数，默认 5，上限 10", required = false) Integer limit) {
        int resultLimit = limit == null ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        if (resultLimit < 1) {
            return error("limit 必须大于 0");
        }
        return invoke(() -> {
            String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            List<MemoryResponse> matched = memories.list(actor, projectId, blankToNull(status), blankToNull(tag))
                    .stream().filter(memory -> matches(memory, normalizedQuery)).toList();
            List<MemorySummary> summaries = matched.stream().limit(resultLimit)
                    .map(this::summary).toList();
            return Map.of("ok", true, "memories", summaries, "matchedCount", matched.size(),
                    "limit", resultLimit, "hasMore", matched.size() > resultLimit);
        });
    }

    /** Read a bounded body window after the permission-checked summary lookup. */
    @ParallelTool
    @Tool(name = "read_memory", description = "读取一条 Memory 的指定正文窗口。memoryId 来自 list_memories；"
            + "默认读取开头 4000 字符，可用 offsetChars 和 maxChars 继续读取，单次最多 8000 字符。"
            + "读取权限由当前触发用户的项目权限决定。")
    public Map<String, Object> readMemory(
            @ToolParam(description = "list_memories 返回的 Memory ID") String memoryId,
            @ToolParam(description = "正文起始字符位置，默认 0", required = false) Integer offsetChars,
            @ToolParam(description = "读取字符数，默认 4000，上限 8000", required = false) Integer maxChars) {
        int offset = offsetChars == null ? 0 : offsetChars;
        int requestedLength = maxChars == null ? DEFAULT_READ_CHARS : maxChars;
        if (offset < 0 || requestedLength < 1) {
            return error("offsetChars 不能小于 0，maxChars 必须大于 0");
        }
        int length = Math.min(requestedLength, MAX_READ_CHARS);
        return invoke(() -> {
            MemoryResponse memory = memories.get(actor, projectId, Long.valueOf(memoryId));
            String content = Objects.toString(memory.getContent(), "");
            if (offset > content.length()) {
                return error("offsetChars 超过 Memory 正文长度");
            }
            int end = (int) Math.min((long) content.length(), (long) offset + length);
            boolean truncated = end < content.length();
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("ok", true);
            result.put("id", memory.getId());
            result.put("title", memory.getTitle());
            result.put("category", memory.getCategory());
            result.put("tags", memory.getTags());
            result.put("status", memory.getStatus());
            result.put("content", content.substring(offset, end));
            result.put("offsetChars", offset);
            result.put("totalChars", content.length());
            result.put("truncated", truncated);
            if (truncated) {
                result.put("nextOffsetChars", end);
            }
            return result;
        });
    }

    /** Create through MemoryService so project-admin publication and member draft rules stay shared. */
    @Tool(name = "create_memory", description = "记录经确认、可复用的项目知识。普通项目成员创建为 DRAFT，"
            + "Project Admin 创建后按现有规则直接 APPROVED；仅在事实已确认且后续工作确有复用价值时调用。"
            + "返回实际状态，不能将临时推测写成 Memory。")
    public Map<String, Object> createMemory(
            @ToolParam(description = "知识标题，最多 255 字符") String title,
            @ToolParam(description = "已确认的项目知识正文") String content,
            @ToolParam(description = "知识分类标识，如 ENGINEERING_DECISION") String category,
            @ToolParam(description = "标签列表", required = false) List<String> tags) {
        if (title == null || title.isBlank() || title.length() > 255
                || content == null || content.isBlank()
                || category == null || category.isBlank() || category.length() > 64) {
            return error("Memory 标题、正文和分类不能为空，且标题最多 255 字符、分类最多 64 字符");
        }
        return invoke(() -> {
            MemoryCreateRequest request = new MemoryCreateRequest();
            request.setTitle(title);
            request.setContent(content);
            request.setCategory(category);
            request.setTags(tags);
            return memoryResult(memories.create(actor, projectId, request));
        });
    }

    /** Edit fields through MemoryService, which enforces ownership, admin access and editable states. */
    @Tool(name = "update_memory", description = "更新当前用户有权编辑的 Memory。只传入需要修改的字段；"
            + "仅创建者或 Project Admin 可编辑 DRAFT/PENDING_REVIEW，已批准、已拒绝和已归档状态的限制由现有规则执行。"
            + "返回实际状态；遇到状态冲突时说明限制，不要尝试绕过。")
    public Map<String, Object> updateMemory(
            @ToolParam(description = "list_memories 返回的 Memory ID") String memoryId,
            @ToolParam(description = "新标题，最多 255 字符", required = false) String title,
            @ToolParam(description = "新正文", required = false) String content,
            @ToolParam(description = "新分类，最多 64 字符", required = false) String category,
            @ToolParam(description = "新标签列表；空列表会清除标签", required = false) List<String> tags) {
        if (title != null && title.length() > 255 || category != null && category.length() > 64) {
            return error("Memory 标题最多 255 字符，分类最多 64 字符");
        }
        return invoke(() -> {
            MemoryUpdateRequest request = new MemoryUpdateRequest();
            request.setTitle(title);
            request.setContent(content);
            request.setCategory(category);
            request.setTags(tags);
            return memoryResult(memories.update(actor, projectId, parseId(memoryId), request));
        });
    }

    /** Submit an owned draft or rejected Memory for admin review. */
    @Tool(name = "submit_memory_review", description = "提交当前用户有权操作的 DRAFT 或 REJECTED Memory 供 Project Admin 审核；"
            + "审核中或其他状态不可重复提交。返回实际状态。")
    public Map<String, Object> submitMemoryReview(
            @ToolParam(description = "list_memories 返回的 Memory ID") String memoryId) {
        return invoke(() -> memoryResult(memories.submitReview(actor, projectId, parseId(memoryId))));
    }

    /** Approve a pending Memory; admin permission and approved-context budget remain enforced by MemoryService. */
    @Tool(name = "approve_memory", description = "批准待审核 Memory 并发布。仅 Project Admin 可批准 PENDING_REVIEW；"
            + "现有已批准内容字符预算也会继续检查。返回实际状态或限制原因。")
    public Map<String, Object> approveMemory(
            @ToolParam(description = "list_memories 返回的 Memory ID") String memoryId) {
        return invoke(() -> memoryResult(memories.approve(actor, projectId, parseId(memoryId))));
    }

    /** Reject a pending Memory and retain the review reason. */
    @Tool(name = "reject_memory", description = "驳回待审核 Memory 并记录原因。仅 Project Admin 可驳回 PENDING_REVIEW；"
            + "返回实际状态或限制原因。")
    public Map<String, Object> rejectMemory(
            @ToolParam(description = "list_memories 返回的 Memory ID") String memoryId,
            @ToolParam(description = "驳回原因，最多 2048 字符") String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 2048) {
            return error("驳回原因不能为空且最多 2048 字符");
        }
        return invoke(() -> memoryResult(memories.reject(actor, projectId, parseId(memoryId), reason)));
    }

    /** Archive an approved Memory; admin permission remains enforced by MemoryService. */
    @Tool(name = "archive_memory", description = "归档已批准 Memory。仅 Project Admin 可归档 APPROVED；"
            + "归档会释放 Agent 上下文 Memory 字符预算。返回实际状态或限制原因。")
    public Map<String, Object> archiveMemory(
            @ToolParam(description = "list_memories 返回的 Memory ID") String memoryId) {
        return invoke(() -> memoryResult(memories.archive(actor, projectId, parseId(memoryId))));
    }

    private Map<String, Object> memoryResult(MemoryResponse memory) {
        return Map.of("ok", true, "memory", new MemoryResult(memory.getId(), memory.getTitle(),
                memory.getCategory(), memory.getTags(), memory.getStatus(), memory.getRejectionReason()));
    }

    private Long parseId(String memoryId) {
        return Long.valueOf(memoryId == null ? "" : memoryId.trim());
    }

    private boolean matches(MemoryResponse memory, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return contains(memory.getTitle(), query)
                || contains(memory.getContent(), query)
                || contains(memory.getCategory(), query)
                || memory.getTags() != null && memory.getTags().stream().anyMatch(tag -> contains(tag, query));
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private MemorySummary summary(MemoryResponse memory) {
        String content = Objects.toString(memory.getContent(), "");
        String preview = content.length() <= PREVIEW_CHARS ? content : content.substring(0, PREVIEW_CHARS) + "...";
        return new MemorySummary(memory.getId(), memory.getTitle(), memory.getCategory(), memory.getTags(),
                memory.getStatus(), preview);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Map<String, Object> invoke(java.util.function.Supplier<Map<String, Object>> action) {
        try {
            return action.get();
        } catch (RuntimeException exception) {
            return error(safeMessage(exception));
        }
    }

    private Map<String, Object> error(String message) {
        return Map.of("ok", false, "error", message);
    }

    private String safeMessage(Throwable throwable) {
        if (throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "Memory 操作失败";
        }
        String firstLine = throwable.getMessage().strip().lines().findFirst().orElse("Memory 操作失败");
        return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200);
    }

    public record MemorySummary(String id, String title, String category, List<String> tags, String status,
                                String contentPreview) {
    }

    public record MemoryResult(String id, String title, String category, List<String> tags, String status,
                               String rejectionReason) {
    }
}
