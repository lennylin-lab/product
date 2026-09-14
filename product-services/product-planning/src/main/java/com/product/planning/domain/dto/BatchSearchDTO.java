package com.product.planning.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * @Auther: chuan
 * @Date: 2025/12/28 - 12 - 28 - 17:35
 * @Description: com.product.planning.domain.dto
 * @version: 1.0
 */
@Data
public class BatchSearchDTO {
    private Long orderId;
    private Long orderLineId;
    // 批次状态
    private String status;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> params;
}
