package qg.qgent.chat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import qg.qgent.chat.dto.ContextMemory;
import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.chat.dto.ContextSkill;
import qg.qgent.chat.dto.GroupContext;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.mapper.MemoryMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.skill.entity.SkillEntity;
import qg.qgent.skill.mapper.SkillMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 群聊上下文组装（点3：聊天上下文管理）。
 * <p>
 * 把项目群聊的历史消息、需求、关联仓库、已发布 Skill 目录与已批准 Memory 组装为 Agent 输入上下文，
 * 供 Agent loop 作为 prompt 输入。
 */
@Service
public class ContextService {
    private static final int DEFAULT_MESSAGE_LIMIT = 50;
    private static final int MAX_MESSAGE_LIMIT = 200;

    private final ChatGroupMapper groupMapper;
    private final MessageMapper messageMapper;
    private final SkillMapper skillMapper;
    private final MemoryMapper memoryMapper;
    private final ProjectAccessService access;
    private final GroupService groupService;
    private final ObjectMapper mapper;

    public ContextService(ChatGroupMapper groupMapper, MessageMapper messageMapper, SkillMapper skillMapper,
                          MemoryMapper memoryMapper, ProjectAccessService access,
                          GroupService groupService, ObjectMapper mapper) {
        this.groupMapper = groupMapper;
        this.messageMapper = messageMapper;
        this.skillMapper = skillMapper;
        this.memoryMapper = memoryMapper;
        this.access = access;
        this.groupService = groupService;
        this.mapper = mapper;
    }

    /**
     * 组装项目群聊的 Agent 输入上下文。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   项目群聊 ID
     * @param limit     近期消息条数（默认 50，上限 200）
     * @return 群聊上下文
     */
    public GroupContext buildForGroup(UUID actor, UUID projectId, UUID groupId, Integer limit) {
        groupService.requireGroupMember(projectId, groupId, actor);
        ChatGroupEntity group = groupMapper.selectById(groupId);
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }
        int messageLimit = Math.min(Math.max(limit == null ? DEFAULT_MESSAGE_LIMIT : limit, 1), MAX_MESSAGE_LIMIT);
        boolean isAdmin = "PROJECT_ADMIN".equals(access.requireProjectMember(projectId, actor));

        // 近期消息：取最新 N 条后反转成旧→新，便于 Agent 理解对话脉络
        List<MessageEntity> newest = messageMapper.selectList(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getGroupId, groupId)
                .orderByDesc(MessageEntity::getSequenceNo)
                .last("limit " + messageLimit));
        Collections.reverse(newest);
        List<ContextMessage> conversation = newest.stream()
                .map(this::toContextMessage)
                .toList();

        // 默认提示只提供可激活 Skill 的目录，禁止把未显式选择的正文提前读入模型上下文。
        List<ContextSkill> skills = skillMapper.listPublishedCatalog(projectId, actor);
        List<ContextMemory> memories = memoryMapper.listMemories(projectId, actor, isAdmin, "APPROVED", null).stream()
                .map(m -> new ContextMemory(m.getTitle(), m.getContent(), m.getCategory())).toList();

        return new GroupContext(group.getId().toString(), projectId.toString(), group.getName(), group.getDescription(),
                conversation, skills, memories);
    }

    /**
     * 在指定项目群聊内按关键字检索历史聊天记录。Skill 与 Memory 不属于聊天检索范围。
     */
    public List<ContextMessage> searchChatHistory(UUID actor, UUID projectId, UUID groupId, String query,
                                                   Integer limit) {
        groupService.requireGroupMember(projectId, groupId, actor);
        if (query == null || query.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CHAT_SEARCH_QUERY_REQUIRED", "聊天检索关键字不能为空");
        }
        int messageLimit = Math.min(Math.max(limit == null ? 10 : limit, 1), 50);
        return messageMapper.searchByQuery(projectId, List.of(groupId), query.trim(), messageLimit).stream()
                .map(this::toContextMessage)
                .toList();
    }

    public ContextMessage readChatMessage(UUID actor, UUID projectId, UUID groupId, UUID messageId) {
        groupService.requireGroupMember(projectId, groupId, actor);
        MessageEntity message = messageMapper.selectById(messageId);
        if (message == null || !groupId.equals(message.getGroupId()))
            throw new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "消息不存在或无权访问");
        return toContextMessage(message);
    }

    /**
     * 读取一条当前项目内可见且已发布的 Skill 正文。调用方必须显式选择该 Skill。
     */
    public SkillEntity activateSkill(UUID actor, UUID projectId, UUID skillId) {
        access.requireProjectMember(projectId, actor);
        SkillEntity skill = skillMapper.findVisiblePublishedById(projectId, actor, skillId);
        if (skill == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_AVAILABLE", "Skill 不存在、未发布或无权访问");
        }
        return skill;
    }

    private String senderType(MessageEntity m) {
        if (m.getAgentId() != null) {
            return "AGENT";
        }
        return m.getAuthorUserId() == null ? "SYSTEM" : "USER";
    }

    private String senderId(MessageEntity m) {
        if (m.getAgentId() != null) {
            return m.getAgentId().toString();
        }
        return m.getAuthorUserId() == null ? null : m.getAuthorUserId().toString();
    }

    /** 提取文本或代码正文；附件仅保留 ID、名称和媒体类型供多模态输入读取。 */
    private ContextMessage toContextMessage(MessageEntity m) {
        Map<?, ?> content = parseContent(m.getContent());
        String type = m.getMessageType();
        String text = "IMAGE".equals(type) || "FILE".equals(type)
                ? ""
                : Objects.toString(content.get("CODE".equals(type) ? "code" : "text"), "");
        String attachmentId = stringField(content, "attachmentId");
        ContextMessage message = new ContextMessage(m.getSequenceNo(), type, senderType(m), senderId(m), text,
                attachmentId, stringField(content, "name"),
                stringField(content, "mediaType"));
        message.setId(m.getId() == null ? null : m.getId().toString());
        message.setReplyToId(m.getReplyToMessageId() == null ? null : m.getReplyToMessageId().toString());
        return message;
    }

    /** 解析 content JSON 为字段映射；空/非法时返回空映射，不抛异常。 */
    private Map<?, ?> parseContent(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(contentJson, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 取 content 字段的字符串值；缺失返回 null。 */
    private String stringField(Map<?, ?> content, String key) {
        Object value = content.get(key);
        return value == null ? null : value.toString();
    }
}
