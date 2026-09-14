package com.product.planning.api;

import com.product.planning.api.dto.PlanningContracts;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 计划域工序任务运行时只读契约（Phase 5；ADR-0002 决策 5，ADR-0004 §1 同步边界——
 * "必须立即得到结果的只读查询"）。
 *
 * <p>消费方：product-execution——任务事件（开始/暂停/恢复/完成）登记前需要（a）确认任务
 * 存在（单体语义：{@code update operation_task} 影响 0 行 → 命令返回失败，任务不存在时
 * 不留事件）；（b）派工主行机台 ID（单体 task_event.resource_id 同源）。该信息在 planning
 * 权属表（operation_task/task_assignment）中，Execution 不得跨服务读表（ADR-0005），
 * 故以此批量只读契约替代单体的进程内查询。</p>
 *
 * <p>通信语义：只读、单次调用、OpenFeign 默认 {@code Retryer.NEVER_RETRY}（不自动重试）；
 * planning 不可达/超时/响应非法时由 Execution fail-closed（命令失败，与单体"同库不可达
 * 即整链失败"等价）。鉴权：调用方透传用户 JWT（ADR-0003 两层校验）。</p>
 */
@FeignClient(name = "product-planning", contextId = "planningTaskRuntimeClient", path = "/internal/planning")
public interface PlanningTaskApi {

    /**
     * 按任务 ID 集合批量查询工序任务运行时（存在性/所属批次/派工机台/当前状态）。
     */
    @PostMapping("/tasks/runtime")
    PlanningContracts.TaskRuntimeResponse taskRuntimes(
            @RequestBody PlanningContracts.TaskRuntimeRequest request);
}
