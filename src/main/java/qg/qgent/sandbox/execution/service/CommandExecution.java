package qg.qgent.sandbox.execution.service;

import qg.qgent.sandbox.execution.entity.ExecutionStatus;

import java.util.Map;
import java.util.UUID;

/**
 * 一条异步命令执行的强类型快照；不对外暴露持久化字段。
 *
 * @param id            执行编号
 * @param sandboxId     执行所属沙箱
 * @param tool          记录的工具名称，用于拒绝非命令执行句柄
 * @param status        当前状态
 * @param exitCode      进程退出码，未结束时为 null
 * @param result        命令输出窗口；排队阶段为空
 * @param failureCode   稳定失败码，正常结束为 null
 * @param failureReason 已脱敏的失败说明
 */
public record CommandExecution(
    Long id,
    UUID sandboxId,
    String tool,
    ExecutionStatus status,
    Integer exitCode,
    Map<String, Object> result,
    String failureCode,
    String failureReason) {

    /**
     * 已结束，不会再变化。
     */
    public boolean terminal() {
        return status.terminal();
    }
}
