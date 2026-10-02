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
 * 群消息接口
 * 群消息的发送与分页拉取。
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
     * 发送消息；回复通过 replyToId 指向原消息。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> send(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
                               @PathVariable UUID groupId, @Valid @RequestBody MessageSendRequest body, HttpServletRequest request) {
        return ok(messageService.send(userId, projectId, groupId, body), request);
    }

    /**
     * 分页查询群消息，新消息在前。
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
     * 断线恢复增量消息：仅返回 sequence 大于 afterSequence 的消息，按 sequence 升序。
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

    /** 停止指定消息启动的 Agent 执行，发送者或项目管理员可操作。 */
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
