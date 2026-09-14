package com.product.demand.api;

import com.product.demand.api.dto.AllocationCommand;
import com.product.demand.api.dto.DemandQueryRequests;
import com.product.demand.api.dto.OrderDTO;
import com.product.demand.api.dto.OrderLineDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 需求域批量查询/预占用契约（Phase 4；ADR-0002 决策 1/5，ADR-0005 §2）。
 *
 * <p>消费方：product-planning（排程版本化输入快照的订单/交期/优先级加载、拆批数量
 * 预占用）；product-master-data（启用路线删除保护链路按产品取订单行 ID）。</p>
 *
 * <p>通信语义（与 product-master-data-api 同一基线）：</p>
 * <ul>
 *   <li>查询为只读，服务快照一致性用；默认<b>不重试</b>（Retryer.NEVER_RETRY）；
 *       超时由消费方配置（planning 基线：connect 2s / read 5s）；超时/不可达按失败处理；</li>
 *   <li>{@code allocation} 为<b>写命令</b>：不自动重试；语义与单体
 *       OrderLineAllocationMapper.allocateQty/releaseQty 逐字一致（见 AllocationCommand）；</li>
 *   <li>鉴权：调用方透传用户 JWT；无用户上下文的异步调用使用 Identity 签发的服务身份令牌
 *       （ADR-0003 服务身份方案，Phase 4 落地，见 planning ServiceTokenProperties）；</li>
 *   <li>版本：{@code snapshotVersion} 为 demand_db 服务权属表 demand_data_version 的
 *       单调计数（订单/订单行任一写事务内 +1），供排程运行前后对比检测输入漂移。</li>
 * </ul>
 */
@FeignClient(name = "product-demand", contextId = "demandBatchQueryClient", path = "/internal/demand")
public interface DemandBatchQueryApi {

    /**
     * 批量加载订单行。{@code orderLineIds} 为 null/空时返回全部订单行。
     */
    @PostMapping("/order-lines/batch")
    OrderDTO.OrderLineBatchResponse getOrderLines(@RequestBody DemandQueryRequests.OrderLineBatchQueryRequest request);

    /**
     * 批量加载订单（交期/优先级）。{@code orderIds} 为 null/空时返回全部订单。
     */
    @PostMapping("/orders/batch")
    OrderDTO.OrderBatchResponse getOrders(@RequestBody DemandQueryRequests.OrderBatchQueryRequest request);

    /**
     * 按产品查询订单行 ID 集合（启用工艺路线删除保护链路，替代单体跨模块查询）。
     * {@code productId} 为空时提供方拒绝。
     */
    @PostMapping("/order-lines/ids-by-product")
    AllocationCommand.OrderLineIdsByProductResponse listOrderLineIdsByProduct(
            @RequestBody DemandQueryRequests.OrderLineIdsByProductRequest request);

    /**
     * 订单行数量预占用/释放命令（单体 OrderLineAllocationMapper 语义；写命令不重试）。
     */
    @PostMapping("/order-lines/allocation")
    AllocationCommand.AllocationResponse allocate(@RequestBody AllocationCommand command);

    /**
     * 当前数据版本计数（排程快照漂移检测轻量端点）。
     */
    @PostMapping("/data-version")
    AllocationCommand.DataVersionResponse getDataVersion();
}
