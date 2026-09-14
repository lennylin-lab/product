package com.product.masterdata.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.product.masterdata.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import com.product.masterdata.common.core.entity.BaseEntity;

import java.util.List;

/**
 * 班次/日历对象 calendar
 *
 * @author product
 * @date 2026-01-01
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "calendar", autoResultMap = true)
public class Calendar  extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 日历ID（主键） */
    @TableId(value = "calendar_id", type = IdType.AUTO )
    private Long calendarId;

    @Excel(name = "日历名称")
    private String calendarName;

    /** 工作日模式（如 Mon-Sat） */
    @Excel(name = "工作日模式")
    @TableField(value = "workday_pattern")
    private String workdayPattern;

    /** 班次开始（HH:MM） */
    @Excel(name = "班次开始")
    @TableField(value = "shift_start")
    private String shiftStart;

    /** 班次结束（HH:MM） */
    @Excel(name = "班次结束")
    @TableField(value = "shift_end")
    private String shiftEnd;

    /** 休息时间段数组（可选） */
    @Excel(name = "休息时间段数组")
    @TableField(value = "breaks", typeHandler = JacksonTypeHandler.class)
    private List<CalendarBreak> breaks;
}
