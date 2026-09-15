package com.product.masterdata.api;

import com.product.masterdata.api.dto.ResourceStatusUpdateRequest;
import com.product.masterdata.api.dto.ResourceStatusUpdateResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 资源状态更新契约（KD3 资源权威状态回写，2026-09-16 异常事件建模增量；ADR-0005
 * 资源权威状态归 master-data，execution 不得直写 master_data_db）。
 *
 * <p>消费方：product-planning（消费 resource.status.changed 后回写权威状态）。</p>
 *
 * <p>通信语义（对齐 {@link MasterDataBatchQueryApi} 基线）：</p>
 * <ul>
 *   <li>写路径命令，消费方<b>不重试</b>（OpenFeign 默认 Retryer.NEVER_RETRY，不得改为
 *       无限重试；事件级重试由消费侧消息重试/DLX 承担）；</li>
 *   <li>超时由消费方配置（planning 基线：connect 2s / read 3s，照抄 demand→master-data
 *       既有配置）；超时/不可达按失败处理，消费方 fail-closed（不 ack，待重试/死信兜底）；</li>
 *   <li>鉴权：网关对 /internal/** 显式拒绝（仅服务间可达）；调用方携带用户 JWT 透传或
 *       服务身份令牌（Identity 签发），提供方本地验签（ADR-0003 两层校验）；</li>
 *   <li>幂等语义（实现取舍，已记录）：目标状态与当前状态一致时<b>静默成功返回且不重复
 *       bump 版本计数</b>——计数器职责是排程快照漂移守卫（主数据语义变化才使在跑排程
 *       失效重排），同状态重写无语义变化，bump 会造成在跑排程的无谓失败；状态真正变化
 *       时更新 + 同事务 bump（漂移守卫依赖同事务性）。</li>
 * </ul>
 */
@FeignClient(name = "product-master-data", contextId = "resourceStatusUpdateClient", path = "/internal/master-data")
public interface ResourceStatusUpdateApi {

    /**
     * 更新资源权威状态：校验资源存在与 {@code toStatus} 合法
     * （AVAILABLE/BUSY/DOWN/MAINTENANCE/OFFSHIFT），非法/不存在一律拒绝；
     * 状态变化 → 更新 resource.status + 同事务版本 bump；同状态重复回写 → 静默成功
     * （不更新、不 bump）。响应携带回写后状态与版本计数（见
     * {@link ResourceStatusUpdateResponse} 的消费方 fail-closed 判定说明）。
     */
    @PostMapping("/resource-status")
    ResourceStatusUpdateResponse updateResourceStatus(@RequestBody ResourceStatusUpdateRequest request);
}
