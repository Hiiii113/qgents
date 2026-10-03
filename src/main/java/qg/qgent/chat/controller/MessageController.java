package qg.qgent.chat.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import qg.qgent.chat.dto.MessageResponse;
import qg.qgent.chat.dto.MessageSendRequest;
import qg.qgent.chat.service.MessageService;
import qg.qgent.agent.service.AgentRunService;
import qg.qgent.common.dto.PageSlice;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.PagedApiResponse;
import qg.qgent.common.web.RequestIdFilter;

import java.util.UUID;

/**
 * 群聊消息接口：发送、分页查询、断线补拉、单条消息定位，以及取消消息触发的 Agent 执行。
 * 消息访问权限由服务层校验，响应携带当前请求的 requestId。
 */
@RestController
@RequestMapping("/api/v1")
public class MessageController {

    private final MessageService messageService;
    private final AgentRunService agentRuns;

    public MessageController(MessageService messageService, AgentRunService agentRuns) {
        this.messageService = messageService;
        this.agentRuns = agentRuns;
    }

    /**
     * 发送消息；回复通过 replyToId 指向原消息，clientMessageId 用于重复发送去重。
     * 新消息持久化并提交事务后处理提及；只有提及 Agent 才启动执行，普通消息仅用于聊天。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> send(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                               @PathVariable UUID groupId, @Valid @RequestBody MessageSendRequest body, HttpServletRequest request) {
        return ok(messageService.send(userId, projectId, groupId, body), request);
    }

    /**
     * 按消息序号倒序查询历史消息；不传 cursor 时从最新消息开始，后续使用返回的 nextCursor 翻页。
     * limit 默认 30，实际取值限制在 1～100。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}/messages")
    public PagedApiResponse<MessageResponse> list(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                                  @PathVariable UUID groupId,
                                                  @RequestParam(value = "cursor", required = false) String cursor,
                                                  @RequestParam(value = "limit", defaultValue = "30") int limit, HttpServletRequest request) {
        PageSlice<MessageResponse> slice = messageService.list(userId, projectId, groupId, cursor, limit);
        return new PagedApiResponse<>(slice.getData(), slice.getPage(),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 断线后补拉新消息：仅返回 sequence 大于 afterSequence 的消息，按 sequence 升序。
     * afterSequence 为非负数；还有下一页时，用本页最后一条消息的 sequence 继续补拉。
     * limit 默认 100，实际取值限制在 1～100。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}/messages/incremental")
    public PagedApiResponse<MessageResponse> listIncremental(@AuthenticationPrincipal UUID userId,
                                                              @PathVariable UUID projectId,
                                                              @PathVariable UUID groupId,
                                                              @RequestParam long afterSequence,
                                                              @RequestParam(defaultValue = "100") int limit,
                                                              HttpServletRequest request) {
        PageSlice<MessageResponse> slice = messageService.listAfterSequence(userId, projectId, groupId,
                afterSequence, limit);
        return new PagedApiResponse<>(slice.getData(), slice.getPage(),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /** 按 ID 查询群消息，供通知定位或读取分页之外的消息使用。 */
    @GetMapping("/projects/{projectId}/groups/{groupId}/messages/{messageId}")
    public ApiResponse<?> getMessage(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                     @PathVariable UUID groupId, @PathVariable UUID messageId,
                                     HttpServletRequest request) {
        return ok(messageService.getMessage(userId, projectId, groupId, messageId), request);
    }

    /**
     * 请求取消指定消息触发的 Agent 执行，仅消息发送者或项目管理员可操作。
     * 将运行中的根 AgentRun 标记为 CANCELLING，由运行循环处理取消；返回本次标记的根运行数量。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/messages/{messageId}/cancel-agent")
    public ApiResponse<?> cancelAgent(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                                      @PathVariable UUID groupId, @PathVariable UUID messageId,
                                      HttpServletRequest request) {
        return ok(java.util.Map.of("cancelledRuns", agentRuns.cancel(userId, projectId, groupId, messageId)), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
