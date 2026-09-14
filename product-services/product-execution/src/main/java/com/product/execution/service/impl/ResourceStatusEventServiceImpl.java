package com.product.execution.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.product.cloud.messaging.api.EventEnvelope;
import com.product.cloud.messaging.api.EventTypes;
import com.product.cloud.messaging.codec.EnvelopeCodec;
import com.product.cloud.messaging.outbox.OutboxPublisher;
import com.product.execution.domain.entity.ResourceStatusEvent;
import com.product.execution.mapper.ResourceStatusEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源状态事件 Service（Phase 5 新增服务能力——单体现状仅存在表与实体，无任何
 * 写入/读取链路，冻结为事实）。
 *
 * <p>语义：登记 resource_status_event 行 + 以 resource.status.changed 事件出站
 * （ADR-0004 §2：Execution → Planning，仅记录/告警用途）——同一本地事务写入
 * （ADR-0004 §3 Outbox）。不驱动任何状态机（单体现状资源状态由机台/资源管理
 * 维护，本端点只做记录）。</p>
 */
@Slf4j
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "product.messaging", name = "enabled", havingValue = "true")
public class ResourceStatusEventServiceImpl extends ServiceImpl<ResourceStatusEventMapper, ResourceStatusEvent> {

    @Autowired
    private OutboxPublisher outboxPublisher;

    /** 登记资源状态事件并发布 resource.status.changed（同事务）。 */
    @Transactional(rollbackFor = Exception.class)
    public ResourceStatusEvent record(ResourceStatusEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(IdWorker.getId());
        }
        if (event.getTime() == null) {
            event.setTime(LocalDateTime.now());
        }
        save(event);
        publishResourceStatusChanged(event);
        return event;
    }

    /** 按资源查询事件（现场追溯）。 */
    public List<ResourceStatusEvent> listByResourceId(Long resourceId) {
        return list(new LambdaQueryWrapper<ResourceStatusEvent>()
                .eq(resourceId != null, ResourceStatusEvent::getResourceId, resourceId)
                .orderByDesc(ResourceStatusEvent::getEventId));
    }

    private void publishResourceStatusChanged(ResourceStatusEvent event) {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventType(EventTypes.RESOURCE_STATUS_CHANGED);
        envelope.setAggregateId(event.getResourceId() == null ? null : String.valueOf(event.getResourceId()));
        String traceId = org.slf4j.MDC.get("traceId");
        envelope.setCorrelationId(traceId == null || traceId.isBlank()
                ? java.util.UUID.randomUUID().toString() : traceId);
        envelope.setOccurredAt(EnvelopeCodec.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceId", event.getResourceId());
        payload.put("fromStatus", event.getFromStatus());
        payload.put("toStatus", event.getToStatus());
        payload.put("reasonCode", event.getReasonCode());
        payload.put("relatedTaskId", event.getRelatedTaskId());
        payload.put("occurredEventId", event.getEventId());
        envelope.setPayload(payload);
        outboxPublisher.append(envelope);
        log.info("resource.status.changed 已入发件箱: resourceId={} toStatus={} eventId={}",
                event.getResourceId(), event.getToStatus(), envelope.getEventId());
    }
}
