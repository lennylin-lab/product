package com.product.execution.domain.dto;

import lombok.Data;

/**
 * 暂停任务请求体（单体 product-domain PauseTaskDTO 的服务化移植；
 * 单体 /execute/event/pause 接收该参数但当前实现未使用，契约冻结）。
 */
@Data
public class PauseTaskDTO {
    private Integer pauseDuration;
}
