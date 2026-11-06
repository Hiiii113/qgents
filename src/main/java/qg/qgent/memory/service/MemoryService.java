package qg.qgent.memory.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import qg.qgent.common.web.ApiException;
import qg.qgent.memory.dto.MemoryCreateRequest;
import qg.qgent.memory.dto.MemoryResponse;
import qg.qgent.memory.dto.MemoryUpdateRequest;
import qg.qgent.memory.entity.MemoryEntity;
import qg.qgent.memory.mapper.MemoryMapper;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.user.dto.UserSummary;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** 项目共享知识：创建、编辑、审核和归档。 */
@Service
public class MemoryService {
    private static final Set<String> SUBMITTABLE = Set.of("DRAFT", "REJECTED");
    private static final Set<String> EDITABLE = Set.of("DRAFT", "PENDING_REVIEW");
    private final MemoryMapper memoryMapper;
    private final ProjectMapper projectMapper;
    private final ProjectAccessService access;
    private final UserMapper userMapper;
    private final EventService eventService;
    private final int maxApprovedMemoryChars;

    public MemoryService(MemoryMapper memoryMapper, ProjectMapper projectMapper,
                         ProjectAccessService access, UserMapper userMapper, EventService eventService,
                         @Value("${app.agent.context.max-approved-memory-chars:12000}") int maxApprovedMemoryChars) {
        this.memoryMapper = memoryMapper;
        this.projectMapper = projectMapper;
        this.access = access;
        this.userMapper = userMapper;
        this.eventService = eventService;
        this.maxApprovedMemoryChars = Math.max(0, maxApprovedMemoryChars);
    }

    /** 手动创建 Memory；管理员直接批准，普通成员保存草稿。 */
    @Transactional
    public MemoryResponse create(Long actor, Long projectId, MemoryCreateRequest request) {
        access.requireProjectMember(projectId, actor);
        MemoryEntity memory = new MemoryEntity();
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
    public List<MemoryResponse> list(Long actor, Long projectId, String status, String tag) {
        String role = access.requireProjectMember(projectId, actor);
        boolean isAdmin = "PROJECT_ADMIN".equals(role);
        return memoryMapper.listMemories(projectId, actor, isAdmin, blankToNull(status), blankToNull(tag))
                .stream().map(this::toResponse).toList();
    }

    /** 获取 Memory；可见性与列表一致：管理员看全部，成员看已批准内容或自己的内容。 */
    public MemoryResponse get(Long actor, Long projectId, Long memoryId) {
        String role = access.requireProjectMember(projectId, actor);
        MemoryEntity memory = requireMemoryInProject(projectId, memoryId);
        boolean visible = "APPROVED".equals(memory.getStatus())
                || memory.getCreatedBy().equals(actor)
                || "PROJECT_ADMIN".equals(role);
        if (!visible) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEMORY_NOT_FOUND", "Memory 不存在或无权访问");
        }
        return toResponse(memory);
    }

    /** 编辑草稿或审核中内容；仅创建者或 Project Admin，且状态为 DRAFT/PENDING_REVIEW。 */
    @Transactional
    public MemoryResponse update(Long actor, Long projectId, Long memoryId, MemoryUpdateRequest request) {
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

    /** 提交审核；仅创建者或 Project Admin，且状态为 DRAFT/REJECTED。 */
    @Transactional
    public MemoryResponse submitReview(Long actor, Long projectId, Long memoryId) {
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
    public MemoryResponse approve(Long actor, Long projectId, Long memoryId) {
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
    public MemoryResponse reject(Long actor, Long projectId, Long memoryId, String reason) {
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
    public MemoryResponse archive(Long actor, Long projectId, Long memoryId) {
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
    private void publish(String event, Long projectId, Long memoryId) {
        Map<String, Object> payload = Map.of(
                "projectId", projectId.toString(),
                "resourceType", "MEMORY",
                "resourceId", memoryId.toString(),
                "eventVersion", 1,
                "updatedAt", LocalDateTime.now(ZoneOffset.UTC).toInstant(ZoneOffset.UTC).toString());
        eventService.publish(projectId, null, event, memoryId.toString(), payload);
    }

    private MemoryEntity requireMemoryInProject(Long projectId, Long memoryId) {
        MemoryEntity memory = memoryMapper.selectById(memoryId);
        if (memory == null || !memory.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEMORY_NOT_FOUND", "Memory 不存在或无权访问");
        }
        return memory;
    }

    /** 锁定项目后读取并锁定待批准或归档的 Memory。 */
    private MemoryEntity requireMemoryInProjectForUpdate(Long projectId, Long memoryId) {
        MemoryEntity memory = memoryMapper.selectByIdForUpdate(memoryId);
        if (memory == null || !memory.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEMORY_NOT_FOUND", "Memory 不存在或无权访问");
        }
        return memory;
    }

    private MemoryEntity requireOwned(Long actor, Long projectId, Long memoryId) {
        access.requireProjectMember(projectId, actor);
        MemoryEntity memory = requireMemoryInProject(projectId, memoryId);
        if (!memory.getCreatedBy().equals(actor)) {
            access.requireProjectAdmin(projectId, actor);
        }
        return memory;
    }

    private MemoryEntity requireEditable(Long actor, Long projectId, Long memoryId) {
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
    private void requireApprovedContextCapacity(Long projectId, MemoryEntity candidate) {
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
    private void lockProjectMemoryBudget(Long projectId) {
        projectMapper.selectByIdForUpdate(projectId);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private MemoryResponse toResponse(MemoryEntity memory) {
        return new MemoryResponse(memory.getId().toString(), memory.getProjectId().toString(), memory.getTitle(),
                memory.getContent(), memory.getCategory(), memory.getTags(), memory.getStatus(),
                userSummary(memory.getCreatedBy()), userSummary(memory.getReviewerId()), memory.getRejectionReason(),
                memory.getReviewedAt(), memory.getCreatedAt());
    }

    private UserSummary userSummary(Long userId) {
        if (userId == null) {
            return null;
        }
        UserEntity user = userMapper.selectById(userId);
        return user == null ? null
                : new UserSummary(user.getId().toString(), user.getDisplayName(), user.getAvatarUrl());
    }
}
