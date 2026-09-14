package com.product.masterdata.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 班次休息时间段
 */
@Data
@NoArgsConstructor
public class CalendarBreak {
    @NotBlank(message = "休息开始时间不能为空")
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "休息开始时间格式必须为HH:mm")
    private String start;
    @NotBlank(message = "休息结束时间不能为空")
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "休息结束时间格式必须为HH:mm")
    private String end;
}
