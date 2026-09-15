package com.product.masterdata.service;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.masterdata.api.dto.ResourceStatusUpdateRequest;
import com.product.masterdata.api.dto.ResourceStatusUpdateResponse;
import com.product.masterdata.common.constant.StatusConstants;
import com.product.masterdata.common.exception.ServiceException;
import com.product.masterdata.common.utils.StringUtils;
import com.product.masterdata.domain.entity.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 资源权威状态更新服务（KD3 资源权威状态回写，2026-09-16 异常事件建模增量）。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>校验：resourceId 必填；toStatus ∈ {AVAILABLE, BUSY, DOWN, MAINTENANCE, OFFSHIFT}；
 *       资源必须存在——任一不满足拒绝（ServiceException 错误契约，消费方 fail-closed）；</li>
 *   <li>状态变化：更新 resource.status + 同事务 {@link MasterDataVersionService#bump()}
 *       （bump 与业务写同事务，要么一起生效要么一起回滚——排程快照漂移守卫依赖它）；</li>
 *   <li>幂等（实现取舍，已记录）：toStatus 与当前状态一致时静默成功——不更新、不 bump
 *       （重复消费/重放同一资源事件不使在跑排程经漂移守卫无谓失败；计数器只在主数据
 *       语义真正变化时递增）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceStatusUpdateService {

    /** 资源状态合法集合（与 resource.status 枚举/StatusConstants 一致）。 */
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            StatusConstants.AVAILABLE_RESOURCE_STATUS,
            StatusConstants.BUSY_RESOURCE_STATUS,
            StatusConstants.DOWN_RESOURCE_STATUS,
            StatusConstants.MAINTENANCE_RESOURCE_STATUS,
            StatusConstants.OFFSHIFT_RESOURCE_STATUS);

    private final MasterDataVersionService versionService;

    /**
     * 更新资源权威状态（同事务 bump，见类注释幂等语义）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ResourceStatusUpdateResponse updateStatus(ResourceStatusUpdateRequest request) {
        if (request == null || request.getResourceId() == null) {
            throw new ServiceException("资源状态回写必须指定资源ID");
        }
        if (StringUtils.isBlank(request.getToStatus()) || !ALLOWED_STATUSES.contains(request.getToStatus())) {
            throw new ServiceException("资源状态回写目标状态非法: " + request.getToStatus());
        }
        Resource resource = Db.lambdaQuery(Resource.class)
                .eq(Resource::getResourceId, request.getResourceId())
                .one();
        if (resource == null) {
            throw new ServiceException("资源不存在: resourceId=" + request.getResourceId());
        }
        if (ALLOWED_STATUSES.contains(resource.getStatus())
                && request.getToStatus().equals(resource.getStatus())) {
            // 同状态重复回写：静默成功，不更新、不重复 bump（幂等语义见类注释）
            log.info("资源状态回写幂等跳过（状态未变化）: resourceId={} status={}",
                    request.getResourceId(), resource.getStatus());
            return response(request.getResourceId(), resource.getStatus(), versionService.currentVersion());
        }
        boolean updated = Db.lambdaUpdate(Resource.class)
                .set(Resource::getStatus, request.getToStatus())
                .eq(Resource::getResourceId, request.getResourceId())
                .update();
        if (!updated) {
            // 并发删除等导致 update 影响 0 行：fail-closed（消费方不 ack，待重试/死信兜底）
            throw new ServiceException("资源状态回写未生效: resourceId=" + request.getResourceId());
        }
        versionService.bump();
        log.info("资源权威状态已回写: resourceId={} {}->{} reasonCode={}",
                request.getResourceId(), resource.getStatus(), request.getToStatus(), request.getReasonCode());
        return response(request.getResourceId(), request.getToStatus(), versionService.currentVersion());
    }

    private ResourceStatusUpdateResponse response(Long resourceId, String status, long snapshotVersion) {
        ResourceStatusUpdateResponse response = new ResourceStatusUpdateResponse();
        response.setResourceId(resourceId);
        response.setStatus(status);
        response.setSnapshotVersion(snapshotVersion);
        return response;
    }
}
