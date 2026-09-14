package com.product.masterdata.domain.dto;

import lombok.Data;

/**
 * @Auther: chuan
 * @Date: 2026/1/1 - 01 - 01 - 02:50
 * @Description: com.product.masterdata.domain.dto
 * @version: 1.0
 */
@Data
public class MachineResource {
    private Long machineId;
    private String orgUnit;
    private String name;
    private Integer tonnage;
    private Integer defaultSetupTimeMin;
    private Long calendarId;
    private String status;
}
