package com.product.masterdata.api.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 产品批量查询响应条目（Phase 3 契约）。
 *
 * <p>数据版本字段方案（baselines.md §2 补充记录，Phase 4 排程版本化输入快照的输入）：</p>
 * <ul>
 *   <li>{@code version}：行级版本 = 所属 product 行 update_time 的 epoch 毫秒（null 记 0）。
 *       主数据任意写路径都会触碰 update_time，故可作为"该行是否变化"的判定依据；</li>
 *   <li>{@link ProductRouteDTO#getRouteVersion()}：工艺路线业务版本（单体 product_route.version
 *       原生字段，如 "v1"）；</li>
 *   <li>响应信封 {@link ProductBatchResponse#getSnapshotVersion()}：master_data_db 服务权属表
 *       master_data_data_version 的单调递增计数（任一主数据写事务内 +1），用于排程运行前后
 *       检测输入是否漂移（ADR-0004 快照一致性）。</li>
 * </ul>
 */
public class ProductDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long productId;

    private String productName;

    private String image;

    private String materialCode;

    private String colorCode;

    /** 行级数据版本：update_time epoch 毫秒（null 记 0）。 */
    private long version;

    /** 产品模具参数（A2 产能模型输入）。 */
    private List<ProductMoldParamDTO> moldParams;

    /** 当前启用的工艺路线（无启用路线时为 null）。 */
    private ProductRouteDTO activeRoute;

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getMaterialCode() {
        return materialCode;
    }

    public void setMaterialCode(String materialCode) {
        this.materialCode = materialCode;
    }

    public String getColorCode() {
        return colorCode;
    }

    public void setColorCode(String colorCode) {
        this.colorCode = colorCode;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public List<ProductMoldParamDTO> getMoldParams() {
        return moldParams;
    }

    public void setMoldParams(List<ProductMoldParamDTO> moldParams) {
        this.moldParams = moldParams;
    }

    public ProductRouteDTO getActiveRoute() {
        return activeRoute;
    }

    public void setActiveRoute(ProductRouteDTO activeRoute) {
        this.activeRoute = activeRoute;
    }

    /** 产品模具参数条目。 */
    public static class ProductMoldParamDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long moldId;

        private BigDecimal cycleTimeSec;

        private Integer cavity;

        private BigDecimal yieldRate;

        private BigDecimal utilization;

        public Long getMoldId() {
            return moldId;
        }

        public void setMoldId(Long moldId) {
            this.moldId = moldId;
        }

        public BigDecimal getCycleTimeSec() {
            return cycleTimeSec;
        }

        public void setCycleTimeSec(BigDecimal cycleTimeSec) {
            this.cycleTimeSec = cycleTimeSec;
        }

        public Integer getCavity() {
            return cavity;
        }

        public void setCavity(Integer cavity) {
            this.cavity = cavity;
        }

        public BigDecimal getYieldRate() {
            return yieldRate;
        }

        public void setYieldRate(BigDecimal yieldRate) {
            this.yieldRate = yieldRate;
        }

        public BigDecimal getUtilization() {
            return utilization;
        }

        public void setUtilization(BigDecimal utilization) {
            this.utilization = utilization;
        }
    }
}
