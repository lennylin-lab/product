package com.product.pps.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 机台运行时聚合信息
 */
@Data
public class MachineRuntimeStatsDTO {
    private Long machineId;
    private LocalDateTime latestEndTime;
    private Long maxSequence;
}
