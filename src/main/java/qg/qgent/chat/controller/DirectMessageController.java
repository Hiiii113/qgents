package qg.qgent.chat.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.chat.dto.DirectConversationCreateRequest;
import qg.qgent.chat.dto.DirectConversationResponse;
import qg.qgent.chat.dto.MessageResponse;
import qg.qgent.chat.dto.MessageSendRequest;
import qg.qgent.chat.service.DirectMessageService;
import qg.qgent.common.dto.PageSlice;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.PagedApiResponse;
import qg.qgent.common.web.RequestIdFilter;

import java.util.List;

/**
 * 私聊接口：会话列表、发起会话、消息收发与已读。
 * 私聊记录只有会话双方可读，项目管理员没有越权入口；消息结构与群聊一致，groupId 即会话 ID。
 */
@RestController
@RequestMapping("/api/v1")
public class DirectMessageController {

    private final DirectMessageService directMessages;

    public DirectMessageController(DirectMessageService directMessages) {
        this.directMessages = directMessages;
    }

    /**
     * 当前用户参与的私聊会话，按最近活跃时间倒序。
     */
    @GetMapping("/projects/{projectId}/direct-messages")
    public ApiResponse<List<DirectConversationResponse>> list(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        HttpServletRequest request) {
        return ok(directMessages.list(userId, projectId), request);
    }

    /**
     * 发起或取回与同一对端的会话；重复调用不会新建。
     */
    @Operation(summary = "发起私聊", description = "同一项目的同一对端永远返回同一会话；对端必须是项目成员或已启用的 Agent。")
    @PostMapping("/projects/{projectId}/direct-messages")
    public ApiResponse<?> create(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @Valid @RequestBody DirectConversationCreateRequest body,
        HttpServletRequest request) {
        return ok(directMessages.create(userId, projectId, body), request);
    }

    /**
     * 会话详情；非参与者按会话不存在处理。
     */
    @GetMapping("/projects/{projectId}/direct-messages/{conversationId}")
    public ApiResponse<?> get(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long conversationId,
        HttpServletRequest request) {
        return ok(directMessages.get(userId, projectId, conversationId), request);
    }

    /**
     * 历史消息分页，新消息在前；不传 cursor 时从最新消息开始。
     */
    @GetMapping("/projects/{projectId}/direct-messages/{conversationId}/messages")
    public PagedApiResponse<MessageResponse> listMessages(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long conversationId,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", defaultValue = "30") int limit,
        HttpServletRequest request) {
        PageSlice<MessageResponse> slice =
            directMessages.messages(userId, projectId, conversationId, cursor, limit);
        return new PagedApiResponse<>(
            slice.getData(),
            slice.getPage(),
            (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 断线后补拉：只返回 sequence 大于 afterSequence 的消息，按 sequence 升序。
     */
    @GetMapping("/projects/{projectId}/direct-messages/{conversationId}/messages/incremental")
    public PagedApiResponse<MessageResponse> listMessagesIncremental(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long conversationId,
        @RequestParam long afterSequence,
        @RequestParam(defaultValue = "100") int limit,
        HttpServletRequest request) {
        PageSlice<MessageResponse> slice =
            directMessages.messagesAfter(userId, projectId, conversationId, afterSequence, limit);
        return new PagedApiResponse<>(
            slice.getData(),
            slice.getPage(),
            (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 按消息 ID 读取会话内的一条消息。
     */
    @GetMapping("/projects/{projectId}/direct-messages/{conversationId}/messages/{messageId}")
    public ApiResponse<?> getMessage(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long conversationId,
        @PathVariable Long messageId,
        HttpServletRequest request) {
        return ok(
            directMessages.message(userId, projectId, conversationId, messageId), request);
    }

    /**
     * 发送私聊消息；对端是 Agent 时提交后自动触发回复，clientMessageId 相同的重试返回原消息。
     */
    @Operation(
        summary = "发送私聊消息",
        description = "对端是 Agent 时无需提及，消息提交后自动运行并以 Agent 身份回复；私聊不支持 mentions。")
    @PostMapping("/projects/{projectId}/direct-messages/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> send(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long conversationId,
        @Valid @RequestBody MessageSendRequest body,
        HttpServletRequest request) {
        return ok(directMessages.send(userId, projectId, conversationId, body), request);
    }

    /**
     * 将当前用户在该会话的已读位置推进到最新消息序号。
     */
    @PostMapping("/projects/{projectId}/direct-messages/{conversationId}/read")
    public ApiResponse<?> markRead(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long conversationId,
        HttpServletRequest request) {
        return ok(directMessages.markRead(userId, projectId, conversationId), request);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
