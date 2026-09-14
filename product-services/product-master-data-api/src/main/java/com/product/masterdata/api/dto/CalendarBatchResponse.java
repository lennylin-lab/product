package com.product.masterdata.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 班次日历批量查询响应信封（Phase 4）。
 *
 * <p>{@code snapshotVersion} 为 master_data_db 单调递增变更计数，供排程快照漂移检测。</p>
 */
public class CalendarBatchResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private long snapshotVersion;

    private List<CalendarDTO> calendars;

    public long getSnapshotVersion() {
        return snapshotVersion;
    }

    public void setSnapshotVersion(long snapshotVersion) {
        this.snapshotVersion = snapshotVersion;
    }

    public List<CalendarDTO> getCalendars() {
        return calendars;
    }

    public void setCalendars(List<CalendarDTO> calendars) {
        this.calendars = calendars;
    }
}
