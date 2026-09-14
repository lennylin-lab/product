package com.product.execution.controller;

import com.product.cloud.messaging.outbox.OutboxMessage;
import com.product.cloud.messaging.replay.EventReplayService;
import com.product.execution.common.core.result.AjaxResult;
import com.product.execution.core.utils.SecurityUtils;
import com.product.execution.domain.entity.ResourceStatusEvent;
import com.product.execution.service.impl.ResourceStatusEventServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行域内部契约端点（Phase 5）：
 *
 * <ul>
 *   <li>{@code POST /internal/execution/resource-status-events}：资源状态事件登记
 *       （写 resource_status_event + outbox 出站 resource.status.changed，同事务）；</li>
 *   <li>{@code GET  /internal/execution/resource-status-events/list}：按资源追溯查询；</li>
 *   <li>{@code GET  /internal/execution/ops/outbox}：发件箱巡检（状态计数 + 行筛选）；</li>
 *   <li>{@code POST /internal/execution/ops/replay-outbox}：人工重放 outbox 事件
 *       （新 eventId + 原 correlationId/payload，ADR-0004 §6；写 ops_audit 留痕）。</li>
 * </ul>
 *
 * <p>鉴权：契约端点与其余端点一致（有效签名 token；网关对 /internal/** 显式拒绝，
 * 仅服务间直连）；{@code /ops/*} 两个端点为运维工具，Phase 6 决策追加管理员门禁
 * {@code @ss.hasPermi('*:*:*')}（Identity 种子 admin 一类管理员可调用，服务身份令牌
 * permissions 为空集不可调用；失败语义与单体权限契约一致：HTTP 200 + code 403）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/internal/execution")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "product.messaging", name = "enabled", havingValue = "true")
public class InternalExecutionController {

    private final ResourceStatusEventServiceImpl resourceStatusEventService;
    private final EventReplayService eventReplayService;
    private final com.product.cloud.messaging.outbox.OutboxDao outboxDao;

    /** 资源状态事件登记。 */
    @PostMapping("/resource-status-events")
    public AjaxResult recordResourceStatusEvent(@RequestBody ResourceStatusEvent event) {
        if (event.getResourceId() == null) {
            return AjaxResult.error("资源状态事件必须指定资源ID");
        }
        ResourceStatusEvent saved = resourceStatusEventService.record(event);
        return AjaxResult.success(saved);
    }

    /** 按资源追溯资源状态事件。 */
    @GetMapping("/resource-status-events/list")
    public AjaxResult listResourceStatusEvents(@RequestParam(required = false) Long resourceId) {
        return AjaxResult.success(resourceStatusEventService.listByResourceId(resourceId));
    }

    /** 发件箱巡检（状态计数 + 可选筛选，供对账脚本/巡检用；管理员门禁，见类注释）。 */
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('*:*:*')")
    @GetMapping("/ops/outbox")
    public AjaxResult outboxInspection(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String aggregateId,
                                       @RequestParam(required = false) String eventType,
                                       @RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : List.of(OutboxMessage.STATUS_PENDING, OutboxMessage.STATUS_PUBLISHED,
                OutboxMessage.STATUS_HALTED)) {
            counts.put(s, outboxDao.countByStatus(s));
        }
        result.put("counts", counts);
        if (status != null || aggregateId != null || eventType != null) {
            List<OutboxMessage> rows = eventReplayService.findOutbox(null, aggregateId, eventType, status, limit);
            result.put("rows", rows);
        }
        return AjaxResult.success(result);
    }

    /** 人工重放 outbox 事件（请求体：{"outboxId": 123}；管理员门禁，见类注释）。 */
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('*:*:*')")
    @PostMapping("/ops/replay-outbox")
    public AjaxResult replayOutbox(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("outboxId");
        if (raw == null) {
            return AjaxResult.error("必须指定 outboxId");
        }
        try {
            long outboxId = Long.parseLong(String.valueOf(raw));
            String newEventId = eventReplayService.replayOutbox(outboxId,
                    operatorOrDefault());
            return AjaxResult.success("重放已入队", newEventId);
        } catch (NumberFormatException e) {
            return AjaxResult.error("outboxId 非法");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    private String operatorOrDefault() {
        try {
            String username = SecurityUtils.getUsername();
            return username == null || username.isBlank() ? "system" : username;
        } catch (Exception e) {
            return "system";
        }
    }
}
