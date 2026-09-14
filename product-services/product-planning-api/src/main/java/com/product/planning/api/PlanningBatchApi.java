package com.product.planning.api;

import com.product.planning.api.dto.PlanningContracts;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 计划排程域内部契约（Phase 4；ADR-0002 决策 1/5，ADR-0005 §2）。
 *
 * <p>消费方：product-demand（订单行详情 ProductionBatchView 填充、删除订单行时级联
 * 清理 planning 批次）；product-master-data（启用工艺路线删除保护的阻塞任务判断）。</p>
 *
 * <p>通信语义：</p>
 * <ul>
 *   <li>{@code batches/by-order-lines}（只读）：demand 订单行详情组装用；默认不重试；
 *       超时由消费方配置；查询失败时消费方按契约降级为空列表并记录告警
 *       （响应形状损坏不阻断订单行只读链路——demand 冻结语义为"详情恒可读"）；</li>
 *   <li>{@code batches/by-order-lines/delete}（<b>写命令，不重试</b>）：与单体
 *       demand 删行级联 {@code delete from production_batch where order_line_id in (...)}
 *       逐字对齐（单体最终行为：只删 production_batch 行，不触碰 operation_task 等
 *       planning 子表——该孤儿数据形态为单体冻结行为，迁移后保持一致）；demand 调用
 *       失败时删除订单行整体失败（fail-closed，与单体同事务级联语义等价）；</li>
 *   <li>{@code batches/has-blocking-tasks}（只读）：master-data 启用路线删除保护；
 *       上游失败按"无法确认无阻塞→拒绝删除"处理（fail-closed，由消费方决定）；</li>
 *   <li>鉴权：调用方透传用户 JWT（ADR-0003 两层校验）；服务本地验签。</li>
 * </ul>
 */
@FeignClient(name = "product-planning", contextId = "planningBatchClient", path = "/internal/planning")
public interface PlanningBatchApi {

    /**
     * 按订单行 ID 集合批量查询生产批次（只读视图，字段与单体
     * OrderLineMapper.selectProductionBatchList 的查询列一致）。
     */
    @PostMapping("/batches/by-order-lines")
    PlanningContracts.BatchesByOrderLinesResponse listBatchesByOrderLines(
            @RequestBody PlanningContracts.BatchesByOrderLinesRequest request);

    /**
     * 按订单行 ID 集合删除生产批次（单体 demand 删行级联语义；写命令，不重试）。
     */
    @PostMapping("/batches/by-order-lines/delete")
    PlanningContracts.DeletedBatchesResponse deleteBatchesByOrderLines(
            @RequestBody PlanningContracts.BatchesByOrderLinesRequest request);

    /**
     * 判断订单行集合下是否存在阻塞状态的工序任务（READY/SCHEDULED/RUNNING）。
     */
    @PostMapping("/batches/has-blocking-tasks")
    PlanningContracts.BlockingTasksResponse hasBlockingTasks(
            @RequestBody PlanningContracts.BatchesByOrderLinesRequest request);
}
