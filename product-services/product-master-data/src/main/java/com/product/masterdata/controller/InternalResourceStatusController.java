package com.product.masterdata.controller;

import com.product.masterdata.api.ResourceStatusUpdateApi;
import com.product.masterdata.api.dto.ResourceStatusUpdateRequest;
import com.product.masterdata.api.dto.ResourceStatusUpdateResponse;
import com.product.masterdata.service.ResourceStatusUpdateService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资源权威状态更新契约端点（{@link ResourceStatusUpdateApi} 实现；KD3，2026-09-16
 * 异常事件建模增量，与 {@link InternalMasterDataController} 同风格挂 /internal/master-data）。
 *
 * <p>鉴权与其余端点一致：有效签名 token（调用方用户 JWT 透传或 Identity 签发的服务身份
 * 令牌，本地验签 ADR-0003 两层校验）；网关对 /internal/** 显式拒绝，仅服务间直连可达。
 * 校验/更新/同事务 bump 语义见 {@link ResourceStatusUpdateService}。</p>
 */
@RestController
@RequestMapping("/internal/master-data")
public class InternalResourceStatusController implements ResourceStatusUpdateApi {

    private final ResourceStatusUpdateService resourceStatusUpdateService;

    public InternalResourceStatusController(ResourceStatusUpdateService resourceStatusUpdateService) {
        this.resourceStatusUpdateService = resourceStatusUpdateService;
    }

    @Override
    public ResourceStatusUpdateResponse updateResourceStatus(@RequestBody ResourceStatusUpdateRequest request) {
        return resourceStatusUpdateService.updateStatus(request);
    }
}
