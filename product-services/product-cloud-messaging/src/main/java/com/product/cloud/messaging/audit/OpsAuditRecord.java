package com.product.cloud.messaging.audit;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运维操作审计行（对账/补偿/人工重放留痕；表 {@code ops_audit}，各服务自有库）。
 */
@Data
public class OpsAuditRecord {

    private Long id;
    /** 动作标识：RECON_DRIFT / RECON_HEAL / REPLAY_OUTBOX / REPLAY_DLQ / ADJUST_ALLOCATION ... */
    private String action;
    /** 操作者：触发命令的用户名，后台任务为 system。 */
    private String operator;
    private String paramsJson;
    /** 结果：OK / DRIFT / FAIL ... */
    private String result;
    private String detail;
    private LocalDateTime createdAt;
}
