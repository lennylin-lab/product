package com.product.masterdata.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 工艺路线（含工序定义）契约条目。
 *
 * <p>{@code routeVersion} 为单体 product_route.version 原生业务版本（如 "v1"）；
 * {@code version} 为行级 update_time epoch 毫秒（null 记 0）。</p>
 */
public class ProductRouteDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long routeId;

    private Long productId;

    /** 工艺/图纸业务版本（单体原生字段）。 */
    private String routeVersion;

    /** 是否启用（1 启用）。 */
    private Integer isActive;

    /** 行级数据版本：update_time epoch 毫秒（null 记 0）。 */
    private long version;

    /** 路线工序定义（按 sequence 升序，与单体读取排序一致）。 */
    private List<RouteOperationDTO> operations;

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getRouteVersion() {
        return routeVersion;
    }

    public void setRouteVersion(String routeVersion) {
        this.routeVersion = routeVersion;
    }

    public Integer getIsActive() {
        return isActive;
    }

    public void setIsActive(Integer isActive) {
        this.isActive = isActive;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public List<RouteOperationDTO> getOperations() {
        return operations;
    }

    public void setOperations(List<RouteOperationDTO> operations) {
        this.operations = operations;
    }

    /** 路线工序定义条目。 */
    public static class RouteOperationDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long opId;

        private String opCode;

        private Integer sequence;

        private String eligibleResourceRule;

        private String stdTimeModel;

        private String queuePolicy;

        public Long getOpId() {
            return opId;
        }

        public void setOpId(Long opId) {
            this.opId = opId;
        }

        public String getOpCode() {
            return opCode;
        }

        public void setOpCode(String opCode) {
            this.opCode = opCode;
        }

        public Integer getSequence() {
            return sequence;
        }

        public void setSequence(Integer sequence) {
            this.sequence = sequence;
        }

        public String getEligibleResourceRule() {
            return eligibleResourceRule;
        }

        public void setEligibleResourceRule(String eligibleResourceRule) {
            this.eligibleResourceRule = eligibleResourceRule;
        }

        public String getStdTimeModel() {
            return stdTimeModel;
        }

        public void setStdTimeModel(String stdTimeModel) {
            this.stdTimeModel = stdTimeModel;
        }

        public String getQueuePolicy() {
            return queuePolicy;
        }

        public void setQueuePolicy(String queuePolicy) {
            this.queuePolicy = queuePolicy;
        }
    }
}
