package com.product.planning.domain.vo;

import com.product.planning.domain.entity.TaskAssignment;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @Auther: chuan
 * @Date: 2026/1/3 - 01 - 03 - 01:08
 * @Description: com.product.planning.domain.vo
 * @version: 1.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TaskAssignmentVO extends TaskAssignment {
    private Long batchId;
    private String opCode;
}
