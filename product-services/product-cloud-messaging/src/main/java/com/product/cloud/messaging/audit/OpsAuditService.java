package com.product.cloud.messaging.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 运维审计服务（对账差异/补偿动作/人工重放必须留痕，implement.md Phase 5 范围 4）。
 */
@Slf4j
public class OpsAuditService {

    private final JdbcTemplate jdbc;

    public OpsAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String action, String operator, String paramsJson, String result, String detail) {
        jdbc.update("""
                        INSERT INTO ops_audit (action, operator, params_json, result, detail, created_at)
                        VALUES (?, ?, ?, ?, ?, NOW(3))
                        """,
                action, operator, abbreviate(paramsJson), result, abbreviate(detail));
        log.info("ops audit: action={} operator={} result={}", action, operator, result);
    }

    public List<OpsAuditRecord> findRecent(String action, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ops_audit");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (action != null && !action.isBlank()) {
            sql.append(" WHERE action = ?");
            args.add(action);
        }
        sql.append(" ORDER BY id DESC LIMIT ").append(Math.max(1, limit));
        return jdbc.query(sql.toString(), (rs, i) -> {
            OpsAuditRecord record = new OpsAuditRecord();
            record.setId(rs.getLong("id"));
            record.setAction(rs.getString("action"));
            record.setOperator(rs.getString("operator"));
            record.setParamsJson(rs.getString("params_json"));
            record.setResult(rs.getString("result"));
            record.setDetail(rs.getString("detail"));
            java.sql.Timestamp created = rs.getTimestamp("created_at");
            record.setCreatedAt(created == null ? null : created.toLocalDateTime());
            return record;
        }, args.toArray());
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 2000 ? text.substring(0, 2000) + "...(截断)" : text;
    }
}
