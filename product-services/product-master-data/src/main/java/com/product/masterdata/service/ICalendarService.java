package com.product.masterdata.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import java.util.List;
import com.product.masterdata.domain.entity.Calendar;

/**
 * 班次/日历Service接口（MyBatis-Plus）
 *
 * @author product
 * @date 2026-01-01
 */
public interface ICalendarService extends IService<Calendar> {

    /**
     * 查询班次/日历
     *
     * @param calendarId 班次/日历主键
     * @return 班次/日历
     */
    Calendar selectCalendarByCalendarId(Long calendarId);

    /**
     * 查询班次/日历列表
     *
     * @param calendar 查询条件
     * @return 班次/日历集合
     */
    List<Calendar> selectCalendarList(Calendar calendar);

    /**
     * 分页查询班次/日历列表
     *
     * @param page      分页参数
     * @param calendar 查询条件
     * @return 分页结果
     */
    Page<Calendar> selectCalendarPage(Page<Calendar> page, Calendar calendar);

    /**
     * 新增班次/日历
     *
     * @param calendar 班次/日历
     * @return 是否成功
     */
    boolean insertCalendar(Calendar calendar);

    /**
     * 批量新增班次/日历
     *
     * @param calendars 班次/日历列表
     * @return 成功条数
     */
    int batchInsertCalendar(List<Calendar> calendars);

    /**
     * 修改班次/日历
     *
     * @param calendar 班次/日历
     * @return 是否成功
     */
    boolean updateCalendar(Calendar calendar);

    /**
     * 批量删除班次/日历
     *
     * @param calendarIds 主键集合
     * @return 是否成功
     */
    boolean deleteCalendarByCalendarIds(String[] calendarIds);

    /**
     * 删除班次/日历信息
     *
     * @param calendarId 主键
     * @return 是否成功
     */
    boolean deleteCalendarByCalendarId(Long calendarId);
}
