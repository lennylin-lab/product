package com.product.masterdata.api.dto;

import java.io.Serializable;

/**
 * 班次日历契约条目（Phase 4 新增，排程版本化输入快照的输入）。
 *
 * <p>字段与单体排程算法消费的 Calendar 列一致（calendar_id / calendar_name /
 * workday_pattern / shift_start / shift_end）；{@code version} 为行级数据版本
 * （update_time epoch 毫秒，null 记 0）。日历变化经响应信封 {@code snapshotVersion}
 * 体现（见 CalendarBatchResponse）。</p>
 */
public class CalendarDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long calendarId;

    private String calendarName;

    /** 工作日模式（如 "Mon-Tue-Wed-Thu-Fri"，单体排程 nextWorkday 消费）。 */
    private String workdayPattern;

    /** 班次开始（LocalTime.parse 兼容格式，如 "08:00"）。 */
    private String shiftStart;

    /** 班次结束（如 "17:00"）。 */
    private String shiftEnd;

    /** 行级数据版本：update_time epoch 毫秒（null 记 0）。 */
    private long version;

    public Long getCalendarId() {
        return calendarId;
    }

    public void setCalendarId(Long calendarId) {
        this.calendarId = calendarId;
    }

    public String getCalendarName() {
        return calendarName;
    }

    public void setCalendarName(String calendarName) {
        this.calendarName = calendarName;
    }

    public String getWorkdayPattern() {
        return workdayPattern;
    }

    public void setWorkdayPattern(String workdayPattern) {
        this.workdayPattern = workdayPattern;
    }

    public String getShiftStart() {
        return shiftStart;
    }

    public void setShiftStart(String shiftStart) {
        this.shiftStart = shiftStart;
    }

    public String getShiftEnd() {
        return shiftEnd;
    }

    public void setShiftEnd(String shiftEnd) {
        this.shiftEnd = shiftEnd;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
