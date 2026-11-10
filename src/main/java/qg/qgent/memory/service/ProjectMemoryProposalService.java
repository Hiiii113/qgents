package qg.qgent.memory.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import qg.qgent.agent.service.AgentService;
import qg.qgent.chat.dto.MessageResponse;
import qg.qgent.chat.dto.MessageSendRequest;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.MessageService;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.entity.ProjectMemoryProposalEntity;
import qg.qgent.memory.mapper.ProjectMemoryProposalMapper;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 项目记忆更新走群聊审批：成员或后台发现的候选发在项目默认群，管理员回复候选消息并 @Agent 后由服务端核验生效。
 * <p>
 * 审批绑定候选消息而不是模型参数；来源群、管理员身份、候选状态与 memory.md 版本都在此校验。
 */
@Service
public class ProjectMemoryProposalService {

    private final ProjectMemoryProposalMapper proposals;
    private final ProjectMemoryService memories;
    private final ProjectAccessService access;
    private final ChatGroupMapper groups;
    private final MessageMapper messages;
    private final ProjectMapper projects;
    private final MessageService chat;
    private final AgentService agents;

    public ProjectMemoryProposalService(ProjectMemoryProposalMapper proposals, ProjectMemoryService memories,
                                        ProjectAccessService access, ChatGroupMapper groups, MessageMapper messages,
                                        ProjectMapper projects, MessageService chat, AgentService agents) {
        this.proposals = proposals;
        this.memories = memories;
        this.access = access;
        this.groups = groups;
        this.messages = messages;
        this.projects = projects;
        this.chat = chat;
        this.agents = agents;
    }

    /**
     * 项目成员在当前群提出项目记忆候选。
     * <p>
     * 受限群向默认群披露的授权规则尚未确定，因此非默认群的候选直接被拒绝：既不发布到默认群，也不写入项目记忆。
     * @param agentRunId 提出候选的 AgentRun，候选随后绑定到该运行的回复消息
     * @return 待审批候选
     */
    @Transactional
    public ProjectMemoryProposalEntity propose(Long actor, Long projectId, Long groupId, Long agentRunId,
                                               String content) {
        // 先按真实用户校验项目成员身份，同时得到候选的基线版本
        ProjectMemoryResponse current = memories.get(actor, projectId);
        return insert(candidate(actor, projectId, groupId, agentRunId, content, current, null));
    }

    /**
     * 后台从群记忆发现候选：默认 Agent 在来源默认群发出普通文本候选消息，候选绑定这条消息等待管理员审批。
     * <p>
     * 先校验来源群与内容，再发消息：受限群的候选不会出现在任何群消息里，也不会进入项目记忆。
     * @param sourceVersion 本次发现依据的群记忆内容版本，用于追溯来源并避免重复发送
     * @return 待审批候选
     */
    @Transactional
    public ProjectMemoryProposalEntity publishDiscovered(Long projectId, Long groupId, String content,
                                                         String sourceVersion) {
        ProjectMemoryProposalEntity proposal = candidate(null, projectId, groupId, null, content,
                memories.read(projectId), sourceVersion);
        MessageSendRequest message = new MessageSendRequest();
        message.setType("TEXT");
        message.setContent(Map.of("text", "从本群记忆中发现可以写入项目记忆的内容，请项目管理员回复本条消息并 @我 批准；"
                + "未批准不会写入。\n\n" + proposal.getContent()));
        // 同一群记忆版本只发一条候选消息：重试命中同一个 clientMessageId 时返回原消息，不重复发送
        message.setClientMessageId("memory-discovery:" + sourceVersion);
        MessageResponse sent = chat.sendAsAgent(agents.defaultAgent(projectId).getId(), groupId, message);
        proposal.setMessageId(Long.valueOf(sent.getId()));
        return insert(proposal);
    }

    /** 项目是否已有待审批候选；后台发现据此避免重复申请。 */
    public boolean hasPending(Long projectId) {
        return proposals.selectPendingId(projectId) != null;
    }

    /**
     * 管理员回复候选消息并 @Agent 后审批生效。
     * <p>
     * 只认真实审批用户：候选必须由本次回复直接引用的 Agent 消息提出，同一项目同一群，
     * 且候选仍是待审批状态。先锁项目行，把同项目的候选审批与 memory.md 写入跨实例串行化；
     * 写文件失败时事务回滚，候选保持待审批，记忆不被修改。
     * @param approvalMessageId 触发本次 Agent 运行的用户消息 ID
     * @return 写入后的项目记忆
     */
    @Transactional
    public ProjectMemoryResponse approve(Long actor, Long projectId, Long groupId, Long approvalMessageId) {
        access.requireProjectAdmin(projectId, actor);
        // 候选状态只锁自己那一行；不同候选并发审批还会竞争同一份 memory.md，所以按项目加锁。
        projects.selectByIdForUpdate(projectId);
        MessageEntity approval = messages.selectById(approvalMessageId);
        if (approval == null || !groupId.equals(approval.getGroupId()) || !actor.equals(approval.getAuthorUserId())) {
            throw proposalNotFound();
        }
        ProjectMemoryProposalEntity proposal = approval.getReplyToMessageId() == null ? null
                : proposals.selectByMessageId(approval.getReplyToMessageId());
        if (proposal == null || !projectId.equals(proposal.getProjectId())
                || !groupId.equals(proposal.getGroupId())) {
            throw proposalNotFound();
        }
        if (!"PENDING".equals(proposal.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMORY_PROPOSAL_NOT_PENDING",
                    "该候选已审批，不能重复执行");
        }
        ProjectMemoryResponse saved = memories.applyApproved(projectId, proposal.getContent(),
                proposal.getBaseVersion());
        if (proposals.markApplied(proposal.getId(), actor, approvalMessageId, saved.getVersion()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMORY_PROPOSAL_NOT_PENDING",
                    "该候选状态已变化，审批未完成");
        }
        return saved;
    }

    /** 把候选绑定到运行结束时发出的 Agent 回复消息；审批回复必须指向这条消息。 */
    public void bindMessage(Long agentRunId, Long messageId) {
        proposals.bindMessage(agentRunId, messageId);
    }

    /** 校验来源群和候选内容，构造待审批候选；消息在发出后绑定。 */
    private ProjectMemoryProposalEntity candidate(Long actor, Long projectId, Long groupId, Long agentRunId,
                                                 String content, ProjectMemoryResponse current,
                                                 String sourceVersion) {
        ChatGroupEntity group = groups.selectById(groupId);
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }
        if (!Boolean.TRUE.equals(group.getDefaultGroup())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MEMORY_SOURCE_GROUP_RESTRICTED",
                    "受限群内容在授权规则明确前不能更新项目记忆，请到项目默认群提出候选");
        }
        String text = content.trim();
        memories.requireContentBudget(text);
        if (current.getContent().trim().equals(text)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEMORY_PROPOSAL_UNCHANGED",
                    "候选内容与当前项目记忆一致");
        }
        ProjectMemoryProposalEntity proposal = new ProjectMemoryProposalEntity();
        proposal.setProjectId(projectId);
        proposal.setGroupId(groupId);
        proposal.setAgentRunId(agentRunId);
        proposal.setContent(text);
        proposal.setBaseVersion(current.getVersion());
        proposal.setSourceMemoryVersion(sourceVersion);
        proposal.setStatus("PENDING");
        proposal.setProposedBy(actor);
        proposal.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return proposal;
    }

    private ProjectMemoryProposalEntity insert(ProjectMemoryProposalEntity proposal) {
        try {
            proposals.insert(proposal);
        } catch (DuplicateKeyException e) {
            // uk_memory_proposal_run / uk_memory_proposal_message 是唯一性的真正保障；并发提出时撞键的调用方拿到同一提示。
            throw new ApiException(HttpStatus.CONFLICT, "MEMORY_PROPOSAL_PENDING",
                    "已有待审批的项目记忆候选，等待管理员审批");
        }
        return proposal;
    }

    /** 状态与来源都不匹配时统一按「没有可审批候选」处理，不暴露其他群的候选。 */
    private ApiException proposalNotFound() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEMORY_PROPOSAL_NOT_FOUND",
                "当前回复没有对应的待审批项目记忆候选");
    }
}
