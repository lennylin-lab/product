package com.product.planning.config;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LOWEST_COST 综合成本模型权重（2026-09-16 成本模型精度提升，KD2 配置化）。
 *
 * <p>综合成本口径（design.md，仅 LOWEST_COST 机台比较器主键消费）：
 * {@code compositeCost = setup-weight×换型时间(分钟) + changeover-count-penalty×换模次数
 * + cross-shift-penalty×跨班次次数 + energy-weight×能耗(预留恒0)}。</p>
 *
 * <p>量纲：全部权重为「分钟等效成本」，与换型时间分钟同量纲直接相加。默认值即业务可读基准：
 * 一次额外换模 ≈ 30 分钟等效成本、一次跨班 ≈ 60 分钟等效成本。权重为 0 表示关闭该因子；
 * 负值为配置错误，启动失败（绑定校验）。yml 默认 + 环境变量覆盖
 * （{@code PRODUCT_PPS_SCHEDULE_COST_MODEL_*}），重启生效。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "product.pps.schedule.cost-model")
public class ProductCostModelProperties implements InitializingBean {

    /** 换型时间权重（分钟等效成本/分钟；默认 1 = 换型分钟原值计入，与历史行为等价）。 */
    private int setupWeight = 1;

    /** 换模次数惩罚（分钟等效成本/次；默认 30 ≈ 一次额外换模等效 30 分钟成本）。 */
    private int changeoverCountPenalty = 30;

    /** 跨班次惩罚（分钟等效成本/次；默认 60 ≈ 一次跨班等效 60 分钟成本）。 */
    private int crossShiftPenalty = 60;

    /** 能耗权重（KD1 预留槽位：能耗因子恒 0，默认 0；master-data 有能耗字段后再接入）。 */
    private int energyWeight = 0;

    @Override
    public void afterPropertiesSet() {
        if (setupWeight < 0 || changeoverCountPenalty < 0 || crossShiftPenalty < 0 || energyWeight < 0) {
            throw new IllegalStateException(
                    "product.pps.schedule.cost-model 权重不允许负值（0=关闭该因子）: setup-weight="
                            + setupWeight + ", changeover-count-penalty=" + changeoverCountPenalty
                            + ", cross-shift-penalty=" + crossShiftPenalty + ", energy-weight=" + energyWeight);
        }
    }
}
