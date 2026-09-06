package com.product.pps.dto;

import lombok.Data;

/**
 * 机台上一次派工快照，用于换型时间计算。
 */
@Data
public class MachineLastAssignmentDTO {

    private String machineId;
    private String taskId;
    private String moldId;
    private Long productId;
    private String materialCode;
    private String colorCode;
}
