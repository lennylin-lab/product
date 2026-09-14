package com.product.masterdata.domain.vo;

import com.product.masterdata.domain.entity.Resource;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @Auther: chuan
 * @Date: 2026/1/1 - 01 - 01 - 03:13
 * @Description: com.product.masterdata.domain.vo
 * @version: 1.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MachineResourceVO extends Resource {
    private Long machineId;
    private Integer tonnage;
    private Integer defaultSetupTimeMin;
    private String calendarName;
    // 是否离班
    private boolean isOffShift;
    // 前端展示状态
    private String effectiveStatus;
}
