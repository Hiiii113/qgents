package qg.qgent.agent.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.agent.dto.AgentApplicationRequest;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;

/**
 * 自定义 Agent 申请与审批：任一项目成员提交，项目管理员在默认群回复公布消息并 @Agent 后由服务端核验生效。
 * <p>
 * 审批绑定公布该申请的 Agent 消息而不是模型参数，且一条公布消息只公布一条申请；
 * 管理员身份、具体申请与申请状态都按真实消息校验。
 * 待审批申请也计入项目 Agent 数量上限，被拒绝的申请释放名额；申请与计数在同一项目行锁内完成。
 */
@Service
public class AgentApplicationService {

    private final AgentMapper agents;
    private final ProjectAccessService access;
    private final ProjectMapper projects;
    private final ChatGroupMapper groups;
    private final MessageMapper messages;
    private final int maxAgentsPerProject;

    public AgentApplicationService(
            AgentMapper agents,
            ProjectAccessService access,
            ProjectMapper projects,
            ChatGroupMapper groups,
            MessageMapper messages,
            @Value("${app.agent.max-per-project:10}") int maxAgentsPerProject) {
        if (maxAgentsPerProject < 1) throw new IllegalArgumentException("max-per-project 必须大于 0");
        this.agents = agents;
        this.access = access;
        this.projects = projects;
        this.groups = groups;
        this.messages = messages;
        this.maxAgentsPerProject = maxAgentsPerProject;
    }

    /**
     * 提交自定义 Agent 申请，保存为待审批记录，此时还不能邀请入群或运行。
     * @param groupId 提出申请的群；由设置页提交时为空，群聊内提出时必须是项目默认群
     */
    @Transactional
    public AgentEntity apply(
            Long actor, Long projectId, Long groupId, AgentApplicationRequest request) {
        access.requireProjectMember(projectId, actor);
        String name = required(request.getName(), 120, "Agent 申请需要 1～120 字符的名称");
        String systemPrompt =
                required(request.getSystemPrompt(), 20000, "Agent 申请需要不超过 20000 字符的系统提示词");
        String description = optional(request.getDescription(), 2000, "Agent 申请的描述不能超过 2000 字符");
        String avatarUrl = optional(request.getAvatarUrl(), 2048, "Agent 申请的头像地址不能超过 2048 字符");
        if (groupId != null) {
            requireDefaultGroup(projectId, groupId);
        }
        // 项目行锁串行化同项目的申请；计数本身是加锁读，才看得到刚提交的申请。
        projects.selectByIdForUpdate(projectId);
        if (agents.countCountedByProject(projectId) >= maxAgentsPerProject) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "AGENT_LIMIT_REACHED",
                    "项目内 Agent 已达上限 " + maxAgentsPerProject + "，请联系项目管理员清理申请");
        }
        AgentEntity application = new AgentEntity();
        application.setProjectId(projectId);
        application.setName(name);
        application.setDescription(description);
        application.setSystemPrompt(systemPrompt);
        application.setAvatarUrl(avatarUrl);
        application.setDefaultAgent(false);
        application.setStatus(AgentEntity.STATUS_PENDING);
        application.setProposedBy(actor);
        application.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        agents.insert(application);
        return application;
    }

    /** 项目内已启用的 Agent，供群设置邀请成员使用。 */
    public List<AgentEntity> enabledAgents(Long actor, Long projectId) {
        access.requireProjectMember(projectId, actor);
        return agents.selectActiveByProject(projectId);
    }

    /** 默认群里待审批的申请：只有项目管理员需要处理队列，申请原文不外泄给其他成员。 */
    public List<AgentEntity> pendingApplications(Long actor, Long projectId, Long groupId) {
        access.requireProjectAdmin(projectId, actor);
        requireDefaultGroup(projectId, groupId);
        return agents.selectPendingByProject(projectId);
    }

    /**
     * 管理员回复公布该申请的 Agent 消息并 @Agent 后审批通过。
     * @param approvalMessageId 触发本次 Agent 运行的用户消息；它的回复对象必须是公布该申请的消息
     */
    @Transactional
    public AgentEntity approve(Long actor, Long projectId, Long groupId, Long approvalMessageId) {
        access.requireProjectAdmin(projectId, actor);
        requireDefaultGroup(projectId, groupId);
        AgentEntity application =
                requireAnnouncedApplication(actor, projectId, groupId, approvalMessageId);
        if (agents.activate(application.getId(), actor) == 0) {
            throw notPending();
        }
        return agents.selectById(application.getId());
    }

    /** 管理员拒绝申请：被拒绝的申请不能激活，也不再占用数量上限。 */
    @Transactional
    public AgentEntity reject(Long actor, Long projectId, Long groupId, Long approvalMessageId) {
        access.requireProjectAdmin(projectId, actor);
        requireDefaultGroup(projectId, groupId);
        AgentEntity application =
                requireAnnouncedApplication(actor, projectId, groupId, approvalMessageId);
        if (agents.reject(application.getId(), actor) == 0) {
            throw notPending();
        }
        return agents.selectById(application.getId());
    }

    /** 把本次运行公布的那一条申请绑定到它的 Agent 回复消息；重复公布时以最新一次为准。 */
    public void bindAnnouncement(Long applicationId, Long messageId) {
        agents.bindAnnouncement(applicationId, messageId);
    }

    /**
     * 按审批回复引用的 Agent 消息定位那条申请；模型因此无法用参数指向另一条申请。
     */
    private AgentEntity requireAnnouncedApplication(
            Long actor, Long projectId, Long groupId, Long approvalMessageId) {
        MessageEntity approval = messages.selectById(approvalMessageId);
        if (approval == null
                || !groupId.equals(approval.getGroupId())
                || !actor.equals(approval.getAuthorUserId())
                || approval.getReplyToMessageId() == null) {
            throw applicationNotFound();
        }
        AgentEntity application = agents.selectByAnnouncedMessageId(approval.getReplyToMessageId());
        if (application == null || !projectId.equals(application.getProjectId())) {
            throw applicationNotFound();
        }
        if (!AgentEntity.STATUS_PENDING.equals(application.getStatus())) {
            throw notPending();
        }
        return application;
    }

    /** 受限群内容与申请公布规则未定时，默认群以外的群不提出、不公布、不审批项目 Agent。 */
    private void requireDefaultGroup(Long projectId, Long groupId) {
        ChatGroupEntity group = groups.selectById(groupId);
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }
        if (!Boolean.TRUE.equals(group.getDefaultGroup())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "AGENT_SOURCE_GROUP_RESTRICTED",
                    "受限群不能申请或审批项目 Agent，请在项目默认群进行");
        }
    }

    private String required(String value, int maxLength, String message) {
        String text = trimmed(value);
        if (text == null || text.length() > maxLength) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_APPLICATION_INVALID", message);
        }
        return text;
    }

    private String optional(String value, int maxLength, String message) {
        String text = trimmed(value);
        if (text != null && text.length() > maxLength) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_APPLICATION_INVALID", message);
        }
        return text;
    }

    private String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ApiException applicationNotFound() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "AGENT_APPLICATION_NOT_FOUND",
                "当前回复没有对应的待审批 Agent 申请；重新公布待审批申请后请回复最新一条");
    }

    private ApiException notPending() {
        return new ApiException(
                HttpStatus.CONFLICT, "AGENT_APPLICATION_NOT_PENDING", "该申请已处理，不能重复审批");
    }
}
