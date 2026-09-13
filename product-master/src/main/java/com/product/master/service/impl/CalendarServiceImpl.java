package com.product.master.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.product.common.exception.ServiceException;
import com.product.domain.entity.Calendar;
import com.product.domain.entity.CalendarBreak;
import com.product.master.mapper.CalendarMapper;
import com.product.master.service.ICalendarService;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 班次/日历Service业务层处理（MyBatis-Plus）
 *
 * @author product
 * @date 2026-01-01
 */
@Service
public class CalendarServiceImpl extends ServiceImpl<CalendarMapper, Calendar> implements ICalendarService {

    /**
     * 查询班次/日历
     *
     * @param calendarId 班次/日历主键
     * @return 班次/日历
     */
    @Override
    public Calendar selectCalendarByCalendarId(Long calendarId) {
        return getById(calendarId);
    }

    /**
     * 查询班次/日历列表
     *
     * @param calendar 查询条件
     * @return 班次/日历集合
     */
    @Override
    public List<Calendar> selectCalendarList(Calendar calendar) {
        return list(buildQueryWrapper(calendar));
    }

    /**
     * 分页查询班次/日历列表
     *
     * @param page      分页参数
     * @param calendar 查询条件
     * @return 分页结果
     */
    @Override
    public Page<Calendar> selectCalendarPage(Page<Calendar> page, Calendar calendar) {
        return this.page(page, buildQueryWrapper(calendar));
    }

    /**
     * 新增班次/日历
     *
     * @param calendar 班次/日历
     * @return 是否成功
     */
    @Override
    public boolean insertCalendar(Calendar calendar) {
        validateCalendarTime(calendar);
        boolean saved = save(calendar);
        return saved;
    }

    /**
     * 批量新增班次/日历
     *
     * @param calendars 班次/日历列表
     * @return 成功条数
     */
    @Override
    public int batchInsertCalendar(List<Calendar> calendars) {
        if (CollectionUtils.isEmpty(calendars)) {
            return 0;
        }
        for (Calendar calendar : calendars) {
            validateCalendarTime(calendar);
        }
        boolean success = saveBatch(calendars);
        return success ? calendars.size() : 0;
    }

    /**
     * 修改班次/日历
     *
     * @param calendar 班次/日历
     * @return 是否成功
     */
    @Override
    public boolean updateCalendar(Calendar calendar) {
        validateCalendarTime(calendar);
        boolean updated = updateById(calendar);
        return updated;
    }

    /**
     * 批量删除班次/日历
     *
     * @param calendarIds 主键集合
     * @return 是否成功
     */
    @Override
    public boolean deleteCalendarByCalendarIds(String[] calendarIds) {
        if (calendarIds == null || calendarIds.length == 0) {
            return false;
        }
        return removeByIds(Arrays.asList(calendarIds));
    }

    /**
     * 删除班次/日历信息
     *
     * @param calendarId 主键
     * @return 是否成功
     */
    @Override
    public boolean deleteCalendarByCalendarId(Long calendarId) {
        return removeById(calendarId);
    }

    /**
     * 构建查询条件
     */
    private LambdaQueryWrapper<Calendar> buildQueryWrapper(Calendar calendar) {
        LambdaQueryWrapper<Calendar> wrapper = new LambdaQueryWrapper<>();
        if (calendar == null) {
            return wrapper;
        }
        wrapper.eq(calendar.getWorkdayPattern() != null, Calendar::getWorkdayPattern, calendar.getWorkdayPattern());
        wrapper.eq(calendar.getShiftStart() != null, Calendar::getShiftStart, calendar.getShiftStart());
        wrapper.eq(calendar.getShiftEnd() != null, Calendar::getShiftEnd, calendar.getShiftEnd());
        // breaks 为 JSON 数组，默认不作为等值条件过滤
        return wrapper;
    }

    private void validateCalendarTime(Calendar calendar) {
        if (calendar == null) {
            return;
        }
        String shiftStart = calendar.getShiftStart();
        String shiftEnd = calendar.getShiftEnd();
        if (shiftStart != null && shiftEnd != null && shiftEnd.compareTo(shiftStart) <= 0) {
            throw new ServiceException("班次时间必须在同一天，结束时间需晚于开始时间");
        }
        List<CalendarBreak> breaks = calendar.getBreaks();
        if (breaks == null) {
            return;
        }
        for (CalendarBreak item : breaks) {
            if (item == null) {
                continue;
            }
            String start = item.getStart();
            String end = item.getEnd();
            if (start == null || end == null) {
                throw new ServiceException("休息时间开始与结束不能为空");
            }
            if (end.compareTo(start) <= 0) {
                throw new ServiceException("休息时间必须在同一天，结束时间需晚于开始时间");
            }
            if (shiftStart != null && start.compareTo(shiftStart) < 0) {
                throw new ServiceException("休息开始时间不能早于班次开始时间");
            }
            if (shiftEnd != null && end.compareTo(shiftEnd) > 0) {
                throw new ServiceException("休息结束时间不能晚于班次结束时间");
            }
        }
        boolean hasOverlap = breaks.stream()
                .filter(Objects::nonNull)
                .anyMatch(current -> breaks.stream()
                        .filter(Objects::nonNull)
                        .anyMatch(other -> current != other
                                && current.getStart() != null
                                && current.getEnd() != null
                                && other.getStart() != null
                                && other.getEnd() != null
                                && current.getStart().compareTo(other.getEnd()) < 0
                                && current.getEnd().compareTo(other.getStart()) > 0));
        if (hasOverlap) {
            throw new ServiceException("休息时间段存在重叠");
        }
    }
}
