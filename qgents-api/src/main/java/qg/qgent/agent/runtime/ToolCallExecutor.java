package qg.qgent.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.tool.ToolCallback;
import qg.qgent.agent.tool.ParallelToolCallback;
import qg.qgent.sandbox.dto.WorkerExecutionTraceContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 连续的并行工具使用有界执行池；其他调用形成屏障，结果保持模型顺序。 */
final class ToolCallExecutor {
    private final ObjectMapper json;
    private final int maxParallel;

    ToolCallExecutor(ObjectMapper json, int maxParallel) {
        if (maxParallel < 1 || maxParallel > 16) throw new IllegalArgumentException("max-parallel-tool-calls 必须为 1～16");
        this.json = json;
        this.maxParallel = maxParallel;
    }

    Result execute(List<ToolCall> calls, Map<String, ToolCallback> callbacks, BooleanSupplier cancelled) {
        List<ToolResponse> results = new ArrayList<>(Collections.nCopies(calls.size(), null));
        UUID runId = WorkerExecutionTraceContext.currentAgentRunId();
        Map<String, String> context = MDC.getCopyOfContextMap();
        try (ExecutorService pool = Executors.newFixedThreadPool(maxParallel,
                Thread.ofVirtual().name("agent-tool-", 0).factory())) {
            for (int next = 0; next < calls.size();) {
                if (stopped(cancelled)) return new Result(results, true);
                if (!(callbacks.get(calls.get(next).name()) instanceof ParallelToolCallback)) {
                    results.set(next, invoke(calls.get(next), callbacks));
                    next++;
                    continue;
                }
                int end = next;
                while (end < calls.size() && callbacks.get(calls.get(end).name()) instanceof ParallelToolCallback) end++;
                if (parallel(pool, calls, callbacks, next, end, results, cancelled, runId, context))
                    return new Result(results, true);
                next = end;
            }
        }
        return new Result(results, stopped(cancelled));
    }

    private boolean parallel(ExecutorService pool, List<ToolCall> calls, Map<String, ToolCallback> callbacks,
            int start, int end, List<ToolResponse> results, BooleanSupplier cancelled,
            UUID runId, Map<String, String> context) {
        var completed = new ExecutorCompletionService<CompletedCall>(pool);
        int next = start, active = 0;
        boolean stopped = false;
        while (next < end || active > 0) {
            stopped |= stopped(cancelled);
            while (!stopped && next < end && active < maxParallel) {
                int index = next++;
                completed.submit(() -> {
                    UUID previousRun = WorkerExecutionTraceContext.currentAgentRunId();
                    Map<String, String> previousContext = MDC.getCopyOfContextMap();
                    WorkerExecutionTraceContext.set(runId);
                    setMdc(context);
                    try { return new CompletedCall(index, invoke(calls.get(index), callbacks)); }
                    finally { WorkerExecutionTraceContext.set(previousRun); setMdc(previousContext); }
                });
                active++;
                stopped |= stopped(cancelled);
            }
            if (active == 0) break;
            try {
                var future = completed.poll(100, TimeUnit.MILLISECONDS);
                if (future == null) continue;
                CompletedCall result = future.get();
                results.set(result.index(), result.response());
                active--;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return true;
            } catch (ExecutionException exception) {
                throw new IllegalStateException("工具调度失败", exception.getCause());
            }
        }
        // 取消后不补入新调用，已启动的调用全部结算后才释放根运行环境。
        return stopped;
    }

    private ToolResponse invoke(ToolCall call, Map<String, ToolCallback> callbacks) {
        ToolCallback callback = callbacks.get(call.name());
        String result;
        if (callback == null) result = error("TOOL_NOT_FOUND", "本轮没有工具：" + call.name());
        else {
            try { result = callback.call(call.arguments()); }
            catch (RuntimeException exception) {
                result = error("TOOL_FAILED", exception.getMessage() == null ? "工具执行失败" : exception.getMessage());
            }
        }
        return new ToolResponse(call.id(), call.name(), result);
    }

    private String error(String code, String message) {
        try { return json.writeValueAsString(Map.of("ok", false, "errorCode", code, "error", message)); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法编码工具结果", exception); }
    }

    private static boolean stopped(BooleanSupplier cancelled) {
        return Thread.currentThread().isInterrupted() || cancelled.getAsBoolean();
    }

    private static void setMdc(Map<String, String> context) {
        if (context == null) MDC.clear(); else MDC.setContextMap(context);
    }

    record Result(List<ToolResponse> responses, boolean cancelled) { }
    private record CompletedCall(int index, ToolResponse response) { }
}
