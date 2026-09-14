package com.product.masterdata.api.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 资源批量查询响应信封条目：resource 主行 + 机台/模具扩展 + 能力矩阵。
 *
 * <p>{@code version} 为 resource 行 update_time epoch 毫秒（null 记 0）。机台/模具扩展行
 * 与能力矩阵行无时间戳列（单体基线如此），其变化通过信封 {@code snapshotVersion} 体现。</p>
 */
public class ResourceDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long resourceId;

    private String resourceType;

    private String name;

    private String status;

    private Long calendarId;

    private String orgUnit;

    /** 行级数据版本：update_time epoch 毫秒（null 记 0）。 */
    private long version;

    /** 注塑机扩展（resourceType=MACHINE 时存在）。 */
    private MachineDTO machine;

    /** 机台-模具兼容性行（Phase 4 增量：排程级联选模消费；机台资源才携带）。 */
    private List<MachineMoldCompatibilityDTO> moldCompatibilities;

    /** 模具扩展（resourceType=MOLD 时存在）。 */
    private MoldDTO mold;

    /** 能力矩阵条目。 */
    private List<CapabilityDTO> capabilities;

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCalendarId() {
        return calendarId;
    }

    public void setCalendarId(Long calendarId) {
        this.calendarId = calendarId;
    }

    public String getOrgUnit() {
        return orgUnit;
    }

    public void setOrgUnit(String orgUnit) {
        this.orgUnit = orgUnit;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public MachineDTO getMachine() {
        return machine;
    }

    public void setMachine(MachineDTO machine) {
        this.machine = machine;
    }

    public List<MachineMoldCompatibilityDTO> getMoldCompatibilities() {
        return moldCompatibilities;
    }

    public void setMoldCompatibilities(List<MachineMoldCompatibilityDTO> moldCompatibilities) {
        this.moldCompatibilities = moldCompatibilities;
    }

    public MoldDTO getMold() {
        return mold;
    }

    public void setMold(MoldDTO mold) {
        this.mold = mold;
    }

    public List<CapabilityDTO> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(List<CapabilityDTO> capabilities) {
        this.capabilities = capabilities;
    }

    /** 注塑机扩展条目。 */
    public static class MachineDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long machineId;

        private Integer tonnage;

        private Integer defaultSetupTimeMin;

        public Long getMachineId() {
            return machineId;
        }

        public void setMachineId(Long machineId) {
            this.machineId = machineId;
        }

        public Integer getTonnage() {
            return tonnage;
        }

        public void setTonnage(Integer tonnage) {
            this.tonnage = tonnage;
        }

        public Integer getDefaultSetupTimeMin() {
            return defaultSetupTimeMin;
        }

        public void setDefaultSetupTimeMin(Integer defaultSetupTimeMin) {
            this.defaultSetupTimeMin = defaultSetupTimeMin;
        }
    }

    /** 模具扩展条目。 */
    public static class MoldDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long moldId;

        private String moldCode;

        private Integer cavity;

        private String moldStatus;

        private LocalDate nextMaintDue;

        public Long getMoldId() {
            return moldId;
        }

        public void setMoldId(Long moldId) {
            this.moldId = moldId;
        }

        public String getMoldCode() {
            return moldCode;
        }

        public void setMoldCode(String moldCode) {
            this.moldCode = moldCode;
        }

        public Integer getCavity() {
            return cavity;
        }

        public void setCavity(Integer cavity) {
            this.cavity = cavity;
        }

        public String getMoldStatus() {
            return moldStatus;
        }

        public void setMoldStatus(String moldStatus) {
            this.moldStatus = moldStatus;
        }

        public LocalDate getNextMaintDue() {
            return nextMaintDue;
        }

        public void setNextMaintDue(LocalDate nextMaintDue) {
            this.nextMaintDue = nextMaintDue;
        }
    }

    /** 资源能力条目（resource_capability）。 */
    public static class CapabilityDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        private String opCode;

        private Long productId;

        private Integer isEnabled;

        private Integer priorityWeight;

        public String getOpCode() {
            return opCode;
        }

        public void setOpCode(String opCode) {
            this.opCode = opCode;
        }

        public Long getProductId() {
            return productId;
        }

        public void setProductId(Long productId) {
            this.productId = productId;
        }

        public Integer getIsEnabled() {
            return isEnabled;
        }

        public void setIsEnabled(Integer isEnabled) {
            this.isEnabled = isEnabled;
        }

        public Integer getPriorityWeight() {
            return priorityWeight;
        }

        public void setPriorityWeight(Integer priorityWeight) {
            this.priorityWeight = priorityWeight;
        }
    }
}
