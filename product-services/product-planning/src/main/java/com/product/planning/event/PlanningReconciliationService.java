package com.product.planning.event;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.cloud.messaging.audit.OpsAuditService;
import com.product.planning.domain.entity.OperationTask;
import com.product.planning.domain.entity.ProductionBatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 计划域状态对账（Phase 5，ADR-0004 §6；implement.md Phase 5 范围 4）。
 *
 * <p>本域不变式（全部本地数据，无跨服务读）：批次状态 = 其任务状态聚合
 * （BatchStatusResolver 冻结规则）。发现漂移 → ops_audit 留痕（RECON_DRIFT）→
 * 自动修复（重算 + 发布 batch.progress.changed，RECON_HEAL）——对账即补偿，
 * 差异收敛不依赖人工。</p>
 *
 * <p>跨域对账（任务事件流水 vs 三域终态、订单/订单行 vs 批次、allocated_qty vs
 * batch_qty 等）由运维对账脚本执行（scratch/phase5/recon.py），其修正动作落
 * 各服务 ops 审计端点。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "product.messaging", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class PlanningReconciliationService {

    private final PlanningEventConsumerService propagationService;
    private final OpsAuditService opsAuditService;

    /** 本域对账（默认 60s 一轮；可经环境变量调整）。 */
    @Scheduled(fixedDelayString = "${product.planning.recon.interval-ms:60000}",
            initialDelayString = "${product.planning.recon.initial-delay-ms:15000}")
    public void reconcile() {
        List<ProductionBatch> batches = Db.lambdaQuery(ProductionBatch.class).list();
        List<Long> drifted = new java.util.ArrayList<>();
        for (ProductionBatch batch : batches) {
            if (batch.getBatchId() == null) {
                continue;
            }
            String expected = expectedBatchStatus(batch.getBatchId(), batch.getStatus());
            if (expected != null && BatchStatusResolver.differs(expected, batch.getStatus())) {
                drifted.add(batch.getBatchId());
            }
        }
        if (drifted.isEmpty()) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain", "planning");
        params.put("invariant", "batch.status = aggregate(task.status)");
        params.put("driftedBatchIds", drifted);
        opsAuditService.record("RECON_DRIFT", "system", toJson(params), "DRIFT",
                "批次状态与任务聚合不一致: " + drifted);
        for (Long batchId : drifted) {
            heal(batchId);
        }
    }

    /** 单批次修复：重算并发布（补偿），结果审计。 */
    @Transactional(rollbackFor = Exception.class)
    public void heal(Long batchId) {
        try {
            boolean changed = propagationService.recomputeBatchStatus(batchId, null);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("batchId", batchId);
            detail.put("changed", changed);
            opsAuditService.record("RECON_HEAL", "system", "{\"batchId\":" + batchId + "}",
                    changed ? "OK" : "NOOP", toJson(detail));
        } catch (Exception e) {
            log.warn("批次状态修复失败（下轮对账重试）: batchId={} err={}", batchId, e.getMessage());
            opsAuditService.record("RECON_HEAL", "system", "{\"batchId\":" + batchId + "}",
                    "FAIL", e.getMessage() == null ? "unknown" : e.getMessage());
        }
    }

    /** 计算批次期望状态（无任务/任务状态全空 → null，表示无判据，不动）。 */
    private String expectedBatchStatus(Long batchId, String currentStatus) {
        List<String> taskStatuses = Db.lambdaQuery(OperationTask.class)
                .select(OperationTask::getStatus)
                .eq(OperationTask::getBatchId, batchId)
                .list()
                .stream()
                .map(OperationTask::getStatus)
                .filter(Objects::nonNull)
                .toList();
        if (taskStatuses.isEmpty()) {
            return null;
        }
        return BatchStatusResolver.resolveBatchStatus(taskStatuses, currentStatus);
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return com.product.cloud.messaging.codec.EnvelopeCodec.mapper().writeValueAsString(map);
        } catch (Exception e) {
            return String.valueOf(map);
        }
    }
}
