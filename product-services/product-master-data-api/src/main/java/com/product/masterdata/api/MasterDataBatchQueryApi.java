package com.product.masterdata.api;

import com.product.masterdata.api.dto.CalendarBatchQueryRequest;
import com.product.masterdata.api.dto.CalendarBatchResponse;
import com.product.masterdata.api.dto.ChangeoverRuleResponse;
import com.product.masterdata.api.dto.DataVersionResponse;
import com.product.masterdata.api.dto.ProductBatchQueryRequest;
import com.product.masterdata.api.dto.ProductBatchResponse;
import com.product.masterdata.api.dto.ProductExistenceResponse;
import com.product.masterdata.api.dto.ResourceBatchQueryRequest;
import com.product.masterdata.api.dto.ResourceBatchResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 主数据域批量查询契约（Phase 3；ADR-0002 决策 1/5，ADR-0005 §2）。
 *
 * <p>消费方：product-demand（订单行引用产品存在性校验）；Phase 4 起 product-planning
 * （排程版本化输入快照的批量加载，替代算法循环内的细粒度 RPC/N+1）。</p>
 *
 * <p>通信语义（design.md §4 落地）：</p>
 * <ul>
 *   <li>均为只读查询，但服务于写路径前置校验/快照一致性，消费方默认<b>不重试</b>
 *       （OpenFeign 默认 Retryer.NEVER_RETRY，不得改为无限重试）；</li>
 *   <li>超时由消费方配置（demand 基线：connect 2s / read 3s）；超时/不可达按失败处理，
 *       由消费方决定 fail-closed（demand 校验拒绝写入）；</li>
 *   <li>鉴权：调用方透传用户 JWT（Authorization 头），提供方本地验签（ADR-0003 两层校验）；
 *       服务内部异步线程无请求上下文时的服务身份令牌机制随 Phase 4 排程任务引入时补充；</li>
 *   <li>版本：见各 DTO 的 version/snapshotVersion 字段说明（baselines.md §2 补充记录）。</li>
 * </ul>
 */
@FeignClient(name = "product-master-data", contextId = "masterDataBatchQueryClient", path = "/internal/master-data")
public interface MasterDataBatchQueryApi {

    /**
     * 校验产品存在性：返回请求 ID 中存在的子集。
     * {@code productIds} 为空视为非法请求（提供方拒绝）。
     */
    @PostMapping("/products/exists")
    ProductExistenceResponse existsProducts(@RequestBody ProductBatchQueryRequest request);

    /**
     * 批量加载产品聚合（产品行 + 模具参数 + 启用工艺路线）。
     * {@code productIds} 为 null/空时返回全部产品。
     */
    @PostMapping("/products/batch")
    ProductBatchResponse getProducts(@RequestBody ProductBatchQueryRequest request);

    /**
     * 批量加载资源聚合（resource 行 + 机台/模具/夹具扩展 + 兼容矩阵 + 能力矩阵）。
     * {@code resourceIds} 为 null/空时返回全部资源。机台条目含模具兼容性行
     * （{@code moldCompatibilities}，Phase 4 增量）；夹具条目含 fixture 扩展
     * （{@code fixture}，2026-09-15 增量，resourceType=FIXTURE 时存在）。
     */
    @PostMapping("/resources/batch")
    ResourceBatchResponse getResources(@RequestBody ResourceBatchQueryRequest request);

    /**
     * 批量加载班次日历（Phase 4，排程输入快照）。
     * {@code calendarIds} 为 null/空时返回全部日历。
     */
    @PostMapping("/calendars/batch")
    CalendarBatchResponse getCalendars(@RequestBody CalendarBatchQueryRequest request);

    /**
     * 当前默认换型规则（Phase 4，排程输入快照；单体 changeover_rule limit 1 语义）。
     * 规则表为空时 {@code rule} 为 null。
     */
    @PostMapping("/changeover-rule/current")
    ChangeoverRuleResponse getCurrentChangeoverRule();

    /**
     * 当前数据版本计数（Phase 4，轻量端点）：排程快照加载前后各调用一次，
     * 不一致即判定输入漂移（漂移规则见 product-planning SnapshotVersionGuard）。
     */
    @PostMapping("/data-version")
    DataVersionResponse getDataVersion();
}
