package qg.qgent.memory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.common.util.UuidV7;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.dto.MemoryCreateRequest;
import qg.qgent.memory.dto.MemoryDraftRequest;
import qg.qgent.memory.dto.MemoryResponse;
import qg.qgent.memory.dto.MemorySourceRef;
import qg.qgent.memory.dto.MemoryUpdateRequest;
import qg.qgent.memory.entity.MemoryEntity;
import qg.qgent.memory.mapper.MemoryMapper;
import qg.qgent.memory.mapper.MemoryMessageSourceMapper;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.user.dto.UserSummary;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** 项目共享知识：创建、AI 草稿、审核和归档；AI 生成的内容始终先保存为草稿。 */
@Service
public class MemoryService {
    private static final Set<String> SUBMITTABLE = Set.of("DRAFT", "REJECTED");
    private static final Set<String> EDITABLE = Set.of("DRAFT", "PENDING_REVIEW");
    // AI 自动沉淀一次性读取的最近消息条数上限（按群内 sequence 倒序取最近 N 条）。
    private static final int RECENT_MESSAGE_LIMIT = 40;
    private static final String DEFAULT_INSTRUCTION = "将最近群聊中值得沉淀的内容沉淀为项目 Memory";
    private static final String AI_SYSTEM_PROMPT = "你是项目知识沉淀助手。根据给定的最近群聊消息，自动甄别其中值得长期复用的"
            + "项目事实、约定或工程决策，输出严格 JSON，格式为 {\"title\":\"简短标题\",\"content\":\"经确认的项目事实陈述，不得编造\","
            + "\"category\":\"如 ENGINEERING_DECISION/ARCHITECTURE_CONSTRAINT\",\"tags\":[\"标签\"]}。";

    private final MemoryMapper memoryMapper;
    private final MemoryMessageSourceMapper sourceMapper;
    private final MessageMapper messageMapper;
    private final ChatGroupMapper chatGroupMapper;
    private final ProjectMapper projectMapper;
    private final ProjectAccessService access;
    private final UserMapper userMapper;
    private final ChatClient chatClient;
    private final ObjectMapper mapper;
    private final EventService eventService;
    private final int maxApprovedMemoryChars;

    @Autowired
    public MemoryService(MemoryMapper memoryMapper, MemoryMessageSourceMapper sourceMapper,
                         MessageMapper messageMapper, ChatGroupMapper chatGroupMapper,
                         ProjectMapper projectMapper, ProjectAccessService access, UserMapper userMapper,
                         ChatClient.Builder chatClientBuilder, ObjectMapper mapper, EventService eventService,
                         @Value("${app.agent.context.max-approved-memory-chars:12000}") int maxApprovedMemoryChars) {
        this.memoryMapper = memoryMapper;
        this.sourceMapper = sourceMapper;
        this.messageMapper = messageMapper;
        this.chatGroupMapper = chatGroupMapper;
        this.projectMapper = projectMapper;
        this.access = access;
        this.userMapper = userMapper;
        this.chatClient = chatClientBuilder.build();
        this.mapper = mapper;
        this.eventService = eventService;
        this.maxApprovedMemoryChars = Math.max(0, maxApprovedMemoryChars);
    }

    /** 供不启动 Spring 上下文的单元测试使用，采用生产默认预算。 */
    public MemoryService(MemoryMapper memoryMapper, MemoryMessageSourceMapper sourceMapper,
                         MessageMapper messageMapper, ChatGroupMapper chatGroupMapper,
                         ProjectMapper projectMapper, ProjectAccessService access, UserMapper userMapper,
                         ChatClient.Builder chatClientBuilder, ObjectMapper mapper, EventService eventService) {
        this(memoryMapper, sourceMapper, messageMapper, chatGroupMapper, projectMapper, access, userMapper,
                chatClientBuilder, mapper, eventService, 12000);
    }

    /** 手动创建 Memory；管理员直接批准，普通成员保存草稿。 */
    @Transactional
    public MemoryResponse create(UUID actor, UUID projectId, MemoryCreateRequest request) {
        access.requireProjectMember(projectId, actor);
        MemoryEntity memory = new MemoryEntity();
        memory.setId(UuidV7.next());
        memory.setProjectId(projectId);
        memory.setCreatedBy(actor);
        memory.setTitle(request.getTitle().trim());
        memory.setContent(request.getContent().trim());
        memory.setCategory(request.getCategory().trim());
        memory.setTags(request.getTags());
        memory.setStatus("DRAFT");
        boolean approved = access.isProjectAdmin(projectId, actor);
        if (approved) {
            lockProjectMemoryBudget(projectId);
            requireApprovedContextCapacity(projectId, memory);
            memory.setStatus("APPROVED");
            memory.setReviewerId(actor);
            memory.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        memoryMapper.insert(memory);
        if (approved) {
            publish("memory.approved", projectId, memory.getId());
        }
        return toResponse(memoryMapper.selectById(memory.getId()));
    }

    /** 查询可见 Memory；未指定状态时返回全部可见状态，支持标签过滤。 */
    public List<MemoryResponse> list(UUID actor, UUID projectId, String status, String tag) {
        String role = access.requireProjectMember(projectId, actor);
        boolean isAdmin = "PROJECT_ADMIN".equals(role);
        return memoryMapper.listMemories(projectId, actor, isAdmin, blankToNull(status), blankToNull(tag))
                .stream().map(this::toResponse).toList();
    }

    /** 获取 Memory；他人非 APPROVED Memory 视为不可见。 */
    public MemoryResponse get(UUID actor, UUID projectId, UUID memoryId) {
        access.requireProjectMember(projectId, actor);
        MemoryEntity memory = requireMemoryInProject(projectId, memoryId);
        if (!"APPROVED".equals(memory.getStatus()) && !memory.getCreatedBy().equals(actor)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEMORY_NOT_FOUND", "Memory 不存在或无权访问");
        }
        return toResponse(memory);
    }

    /** 编辑草稿或审核中内容；仅创建者或 Project Admin，且状态为 DRAFT/PENDING_REVIEW。 */
    @Transactional
    public MemoryResponse update(UUID actor, UUID projectId, UUID memoryId, MemoryUpdateRequest request) {
        MemoryEntity memory = requireEditable(actor, projectId, memoryId);
        if (request.getTitle() != null) {
            memory.setTitle(request.getTitle().trim());
        }
        if (request.getContent() != null) {
            memory.setContent(request.getContent().trim());
        }
        if (request.getCategory() != null) {
            memory.setCategory(request.getCategory().trim());
        }
        if (request.getTags() != null) {
            memory.setTags(request.getTags());
        }
        memoryMapper.updateById(memory);
        return toResponse(memoryMapper.selectById(memoryId));
    }

    /** 由当前打开的群自动检索最近聊天，生成 AI 草稿（DRAFT），并记录自动选取的来源消息。 */
    @Transactional
    public MemoryResponse createAiDraft(UUID actor, UUID projectId, MemoryDraftRequest request) {
        access.requireProjectMember(projectId, actor);
        ChatGroupEntity group = chatGroupMapper.selectById(request.getGroupId());
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GROUP_NOT_IN_PROJECT",
                    "项目群聊不存在或不属于该项目");
        }

        // 自动检索：按群内 sequence 倒序取最近 N 条，再还原为时间正序便于 AI 理解对话脉络
        List<MessageEntity> recent = new ArrayList<>(messageMapper.selectList(
                Wrappers.<MessageEntity>lambdaQuery()
                        .eq(MessageEntity::getGroupId, request.getGroupId())
                        .orderByDesc(MessageEntity::getSequenceNo)
                        .last("LIMIT " + RECENT_MESSAGE_LIMIT)));
        recent.sort(Comparator.comparing(MessageEntity::getSequenceNo,
                Comparator.nullsFirst(Long::compareTo)));
        // 空群不消耗 LLM：群内没有任何消息时直接拒绝，避免空上下文生成无意义草稿浪费 token
        if (recent.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GROUP_NO_MESSAGES",
                    "该项目群聊暂无消息，无需沉淀");
        }

        StringBuilder transcript = new StringBuilder();
        for (MessageEntity message : recent) {
            transcript.append("- [").append(message.getMessageType()).append("] ")
                    .append(readMessageText(message.getContent())).append('\n');
        }
        String instruction = request.getInstruction() == null || request.getInstruction().isBlank()
                ? DEFAULT_INSTRUCTION
                : request.getInstruction().trim();

        String content;
        try {
            content = chatClient.prompt().system(AI_SYSTEM_PROMPT)
                    .user("最近群聊消息：\n" + transcript + "\n沉淀指令：" + instruction).call().content();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI_DRAFT_FAILED", "AI 草稿生成失败: " + e.getMessage());
        }
        AiDraft draft = parseAiDraft(content);

        MemoryEntity memory = new MemoryEntity();
        memory.setId(UuidV7.next());
        memory.setProjectId(projectId);
        memory.setCreatedBy(actor);
        memory.setTitle(draft.getTitle());
        memory.setContent(draft.getContent());
        memory.setCategory(draft.getCategory() == null ? "ENGINEERING_DECISION" : draft.getCategory());
        memory.setTags(draft.getTags());
        memory.setStatus("DRAFT");
        memoryMapper.insert(memory);
        for (MessageEntity message : recent) {
            sourceMapper.insertSource(memory.getId(), message.getId());
        }
        return toResponse(memoryMapper.selectById(memory.getId()));
    }

    /** 提交审核；仅创建者或 Project Admin，且状态为 DRAFT/REJECTED。 */
    @Transactional
    public MemoryResponse submitReview(UUID actor, UUID projectId, UUID memoryId) {
        MemoryEntity memory = requireOwned(actor, projectId, memoryId);
        if (!SUBMITTABLE.contains(memory.getStatus())) {
            throw stateConflict(memory.getStatus());
        }
        memory.setStatus("PENDING_REVIEW");
        memory.setSubmittedBy(actor);
        memory.setSubmittedAt(LocalDateTime.now(ZoneOffset.UTC));
        memoryMapper.updateById(memory);
        publish("memory.submit-review", projectId, memoryId);
        return toResponse(memoryMapper.selectById(memoryId));
    }

    /** 批准并发布 Memory（Project Admin）；仅 PENDING_REVIEW。 */
    @Transactional
    public MemoryResponse approve(UUID actor, UUID projectId, UUID memoryId) {
        access.requireProjectAdmin(projectId, actor);
        lockProjectMemoryBudget(projectId);
        MemoryEntity memory = requireMemoryInProjectForUpdate(projectId, memoryId);
        if (!"PENDING_REVIEW".equals(memory.getStatus())) {
            throw stateConflict(memory.getStatus());
        }
        requireApprovedContextCapacity(projectId, memory);
        memory.setStatus("APPROVED");
        memory.setReviewerId(actor);
        memory.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        memoryMapper.updateById(memory);
        publish("memory.approved", projectId, memoryId);
        return toResponse(memoryMapper.selectById(memoryId));
    }

    /** 拒绝 Memory 并给出原因（Project Admin）；仅 PENDING_REVIEW。 */
    @Transactional
    public MemoryResponse reject(UUID actor, UUID projectId, UUID memoryId, String reason) {
        access.requireProjectAdmin(projectId, actor);
        MemoryEntity memory = requireMemoryInProject(projectId, memoryId);
        if (!"PENDING_REVIEW".equals(memory.getStatus())) {
            throw stateConflict(memory.getStatus());
        }
        memory.setStatus("REJECTED");
        memory.setReviewerId(actor);
        memory.setRejectionReason(reason);
        memory.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        memoryMapper.updateById(memory);
        publish("memory.rejected", projectId, memoryId);
        return toResponse(memoryMapper.selectById(memoryId));
    }

    /** 归档 Memory（Project Admin）；仅 APPROVED。 */
    @Transactional
    public MemoryResponse archive(UUID actor, UUID projectId, UUID memoryId) {
        access.requireProjectAdmin(projectId, actor);
        lockProjectMemoryBudget(projectId);
        MemoryEntity memory = requireMemoryInProjectForUpdate(projectId, memoryId);
        if (!"APPROVED".equals(memory.getStatus())) {
            throw stateConflict(memory.getStatus());
        }
        memory.setStatus("ARCHIVED");
        memoryMapper.updateById(memory);
        publish("memory.archived", projectId, memoryId);
        return toResponse(memoryMapper.selectById(memoryId));
    }

    /** 发布知识状态变化，供客户端刷新对应内容。 */
    private void publish(String event, UUID projectId, UUID memoryId) {
        Map<String, Object> payload = Map.of(
                "projectId", projectId.toString(),
                "resourceType", "MEMORY",
                "resourceId", memoryId.toString(),
                "eventVersion", 1,
                "updatedAt", LocalDateTime.now(ZoneOffset.UTC).toInstant(ZoneOffset.UTC).toString());
        eventService.publish(projectId, null, event, memoryId.toString(), payload);
    }

    private MemoryEntity requireMemoryInProject(UUID projectId, UUID memoryId) {
        MemoryEntity memory = memoryMapper.selectById(memoryId);
        if (memory == null || !memory.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEMORY_NOT_FOUND", "Memory 不存在或无权访问");
        }
        return memory;
    }

    /** 锁定项目后读取并锁定待批准或归档的 Memory。 */
    private MemoryEntity requireMemoryInProjectForUpdate(UUID projectId, UUID memoryId) {
        MemoryEntity memory = memoryMapper.selectByIdForUpdate(memoryId);
        if (memory == null || !memory.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEMORY_NOT_FOUND", "Memory 不存在或无权访问");
        }
        return memory;
    }

    private MemoryEntity requireOwned(UUID actor, UUID projectId, UUID memoryId) {
        access.requireProjectMember(projectId, actor);
        MemoryEntity memory = requireMemoryInProject(projectId, memoryId);
        if (!memory.getCreatedBy().equals(actor)) {
            access.requireProjectAdmin(projectId, actor);
        }
        return memory;
    }

    private MemoryEntity requireEditable(UUID actor, UUID projectId, UUID memoryId) {
        MemoryEntity memory = requireOwned(actor, projectId, memoryId);
        if (!EDITABLE.contains(memory.getStatus())) {
            throw stateConflict(memory.getStatus());
        }
        return memory;
    }

    private ApiException stateConflict(String status) {
        return new ApiException(HttpStatus.CONFLICT, "MEMORY_STATE_CONFLICT", "Memory 当前状态不允许该操作: " + status);
    }

    /** 确保批准后的全部项目 Memory 仍可完整注入 Agent 上下文。 */
    private void requireApprovedContextCapacity(UUID projectId, MemoryEntity candidate) {
        long candidateChars = (long) candidate.getTitle().length() + candidate.getContent().length();
        long usedChars = memoryMapper.selectApprovedForUpdate(projectId).stream()
                .mapToLong(memory -> (long) memory.getTitle().length() + memory.getContent().length())
                .sum();
        if (usedChars > (long) maxApprovedMemoryChars - candidateChars) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMORY_APPROVED_CONTEXT_LIMIT_EXCEEDED",
                    "已批准 Memory 超出 Agent 上下文字符预算，请归档或精简既有 Memory 后重试");
        }
    }

    /** 批准和归档前先锁定项目，再计算已批准内容的字符总量。 */
    private void lockProjectMemoryBudget(UUID projectId) {
        projectMapper.selectByIdForUpdate(projectId);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String readMessageText(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return "";
        }
        try {
            Map<?, ?> map = mapper.readValue(contentJson, Map.class);
            Object text = map.get("text");
            return text == null ? contentJson : text.toString();
        } catch (Exception e) {
            return contentJson;
        }
    }

    private AiDraft parseAiDraft(String content) {
        if (content == null || content.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI_DRAFT_FAILED", "AI 未返回内容");
        }
        String json = content;
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = content.substring(start, end + 1);
        }
        try {
            return mapper.readValue(json, AiDraft.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI_DRAFT_FAILED", "AI 返回无法解析");
        }
    }

    /** AI 草稿解析结果（LLM 输出 JSON 的目标结构）。 */
    @Data
    private static class AiDraft {
        private String title;
        private String content;
        private String category;
        private List<String> tags;
    }

    private MemoryResponse toResponse(MemoryEntity memory) {
        List<MemorySourceRef> sources = sourceMapper.selectMessageIds(memory.getId()).stream()
                .map(messageId -> {
                    MessageEntity message = messageMapper.selectById(messageId);
                    MemorySourceRef ref = new MemorySourceRef();
                    ref.setMessageId(messageId);
                    ref.setGroupId(message == null ? null : message.getGroupId());
                    return ref;
                }).toList();
        String source = sources.isEmpty() ? "MANUAL" : "MESSAGE";
        return new MemoryResponse(memory.getId().toString(), memory.getProjectId().toString(), memory.getTitle(),
                memory.getContent(), memory.getCategory(), memory.getTags(), memory.getStatus(),
                userSummary(memory.getCreatedBy()), userSummary(memory.getReviewerId()), memory.getRejectionReason(),
                memory.getReviewedAt(), memory.getCreatedAt(), source, sources);
    }

    private UserSummary userSummary(UUID userId) {
        if (userId == null) {
            return null;
        }
        UserEntity user = userMapper.selectById(userId);
        return user == null ? null
                : new UserSummary(user.getId().toString(), user.getDisplayName(), user.getAvatarUrl());
    }
}
