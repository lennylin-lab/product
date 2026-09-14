package com.product.masterdata.api.dto;

import java.io.Serializable;

/**
 * 默认换型规则响应信封（Phase 4）。
 *
 * <p>{@code rule} 为 changeover_rule 表 {@code limit 1} 的默认规则（与单体
 * loadDefaultChangeoverRule 语义一致）；表为空时为 null。{@code snapshotVersion}
 * 供排程快照漂移检测。</p>
 */
public class ChangeoverRuleResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private long snapshotVersion;

    private ChangeoverRuleDTO rule;

    public long getSnapshotVersion() {
        return snapshotVersion;
    }

    public void setSnapshotVersion(long snapshotVersion) {
        this.snapshotVersion = snapshotVersion;
    }

    public ChangeoverRuleDTO getRule() {
        return rule;
    }

    public void setRule(ChangeoverRuleDTO rule) {
        this.rule = rule;
    }
}
