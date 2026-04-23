package com.product.master.resource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.common.annotation.BizIdPrefix;
import com.product.common.constant.ResourceConstants;
import com.product.common.constant.StatusConstants;
import com.product.common.utils.StringUtils;
import com.product.common.utils.uuid.IdUtils;
import com.product.domain.dto.MachineResource;
import com.product.domain.entity.Calendar;
import com.product.domain.entity.CalendarBreak;
import com.product.domain.entity.Resource;
import com.product.domain.vo.MachineResourceVO;
import com.product.master.domain.entity.Machine;
import com.product.master.resource.mapper.MachineMapper;
import com.product.master.resource.service.IMachineService;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 注塑机扩展信息Service业务层处理（MyBatis-Plus）
 *
 * @author product
 * @date 2026-01-01
 */
@Service
public class MachineServiceImpl extends ServiceImpl<MachineMapper, Machine> implements IMachineService {

    @Autowired
    private MachineMapper machineMapper;

    /**
     * 查询注塑机扩展信息
     *
     * @param machineId 注塑机扩展信息主键
     * @return 注塑机扩展信息
     */
    @Override
    public MachineResourceVO selectMachineByMachineId(String machineId) {
        MachineResourceVO machine = machineMapper.selectMachineByMachineId(machineId);
        fillEffectiveStatus(Collections.singletonList(machine));
        return machine;
    }

    /**
     * 查询注塑机扩展信息列表
     *
     * @param machine 查询条件
     * @return 注塑机扩展信息集合
     */
    @Override
    public List<Machine> selectMachineList(Machine machine) {
        return list(buildQueryWrapper(machine));
    }

    /**
     * 分页查询注塑机扩展信息列表
     *
     * @param page            分页参数
     * @param machineResource 查询条件
     * @return 分页结果
     */
    @Override
    public Page<MachineResourceVO> selectMachinePage(Page<MachineResourceVO> page, MachineResource machineResource) {
        Page<MachineResourceVO> result = machineMapper.selectMachinePage(page, machineResource);
        fillEffectiveStatus(result.getRecords());
        return result;
    }

    /**
     * 新增注塑机扩展信息
     *
     * @param machineResource 注塑机扩展信息
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insertMachine(MachineResource machineResource) {
        Machine machine = new Machine();
        BeanUtils.copyProperties(machineResource, machine);
        // 需要插入资源表
        Resource resource = new Resource();
        BeanUtils.copyProperties(machineResource, resource);
        resource.setResourceId(buildBizId(resource));
        machine.setMachineId(resource.getResourceId());
        resource.setResourceType(ResourceConstants.RESOURCE_TYPE_MACHINE);
        resource.setStatus(StatusConstants.AVAILABLE_RESOURCE_STATUS);
        boolean saveResource = Db.save(resource);
        boolean saveMachine = save(machine);
        return saveResource && saveMachine;
    }

    /**
     * 批量新增注塑机扩展信息
     *
     * @param machines 注塑机扩展信息列表
     * @return 成功条数
     */
    @Override
    public int batchInsertMachine(List<Machine> machines) {
        if (CollectionUtils.isEmpty(machines)) {
            return 0;
        }
        machines.forEach(item -> {
            if (StringUtils.isEmpty(item.getMachineId())) {
                item.setMachineId(buildBizId(item));
            }
        });
        boolean success = saveBatch(machines);
        return success ? machines.size() : 0;
    }

    /**
     * 修改注塑机扩展信息
     *
     * @param machineResource 注塑机扩展信息
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateMachine(MachineResource machineResource) {
        boolean updateResource = Db.lambdaUpdate(Resource.class)
                .set(Resource::getCalendarId, machineResource.getCalendarId())
                .set(Resource::getName, machineResource.getName())
                .eq(Resource::getResourceId, machineResource.getMachineId())
                .update();
        boolean updateMachine = lambdaUpdate().set(Machine::getTonnage, machineResource.getTonnage())
                .set(Machine::getDefaultSetupTimeMin, machineResource.getDefaultSetupTimeMin())
                .eq(Machine::getMachineId, machineResource.getMachineId())
                .update();
        return updateResource && updateMachine;
    }

    /**
     * 批量删除注塑机扩展信息
     *
     * @param machineIds 主键集合
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteMachineByMachineIds(String[] machineIds) {
        if (machineIds == null || machineIds.length == 0) {
            return false;
        }
        boolean removeMachine = removeByIds(Arrays.asList(machineIds));
        boolean removeResource = Db.removeByIds(Arrays.asList(machineIds), Resource.class);
        return removeMachine && removeResource;
    }

    /**
     * 删除注塑机扩展信息信息
     *
     * @param machineId 主键
     * @return 是否成功
     */
    @Override
    public boolean deleteMachineByMachineId(String machineId) {
        boolean removeMachine = removeById(machineId);
        boolean removeResource = Db.removeById(machineId, Resource.class);
        return removeMachine && removeResource;
    }

    @Override
    public boolean down(String machineId) {
        return Db.lambdaUpdate(Resource.class)
                .set(Resource::getStatus, StatusConstants.DOWN_RESOURCE_STATUS)
                .eq(Resource::getResourceId, machineId)
                .update();
    }

    @Override
    public boolean maintenance(String machineId) {
        return Db.lambdaUpdate(Resource.class)
                .set(Resource::getStatus, StatusConstants.MAINTENANCE_RESOURCE_STATUS)
                .eq(Resource::getResourceId, machineId)
                .update();
    }

    @Override
    public boolean restore(String machineId) {
        return Db.lambdaUpdate(Resource.class)
                .set(Resource::getStatus, StatusConstants.AVAILABLE_RESOURCE_STATUS)
                .eq(Resource::getResourceId, machineId)
                .update();
    }

    /**
     * 构建查询条件
     */
    private LambdaQueryWrapper<Machine> buildQueryWrapper(Machine machine) {
        LambdaQueryWrapper<Machine> wrapper = new LambdaQueryWrapper<>();
        if (machine == null) {
            return wrapper;
        }
        wrapper.eq(machine.getTonnage() != null, Machine::getTonnage, machine.getTonnage());
        wrapper.eq(machine.getDefaultSetupTimeMin() != null, Machine::getDefaultSetupTimeMin,
                machine.getDefaultSetupTimeMin());
        return wrapper;
    }

    private void fillEffectiveStatus(List<MachineResourceVO> machines) {
        if (CollectionUtils.isEmpty(machines)) {
            return;
        }
        List<MachineResourceVO> validMachines = machines.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (validMachines.isEmpty()) {
            return;
        }
        Set<Long> calendarIds = validMachines.stream()
                .map(MachineResourceVO::getCalendarId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Calendar> calendarMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(calendarIds)) {
            calendarMap = Db.lambdaQuery(Calendar.class)
                    .in(Calendar::getCalendarId, calendarIds)
                    .list()
                    .stream()
                    .collect(Collectors.toMap(Calendar::getCalendarId, item -> item, (a, b) -> a));
        }
        LocalDateTime now = LocalDateTime.now();
        for (MachineResourceVO machine : validMachines) {
            Calendar calendar = calendarMap.get(machine.getCalendarId());
            boolean isOffShift = isOffShift(calendar, now);
            machine.setOffShift(isOffShift);
            String status = machine.getStatus();
            if (StatusConstants.DOWN_RESOURCE_STATUS.equals(status)
                    || StatusConstants.MAINTENANCE_RESOURCE_STATUS.equals(status)) {
                machine.setEffectiveStatus(status);
            } else if (isOffShift) {
                machine.setEffectiveStatus(StatusConstants.OFFSHIFT_RESOURCE_STATUS);
            } else {
                machine.setEffectiveStatus(StatusConstants.AVAILABLE_RESOURCE_STATUS);
            }
        }
    }

    private boolean isOffShift(Calendar calendar, LocalDateTime now) {
        if (calendar == null) {
            return true;
        }
        String shiftStart = calendar.getShiftStart();
        String shiftEnd = calendar.getShiftEnd();
        if (StringUtils.isEmpty(shiftStart) || StringUtils.isEmpty(shiftEnd)) {
            return true;
        }
        if (!isWorkday(calendar.getWorkdayPattern(), now.getDayOfWeek())) {
            return true;
        }
        LocalTime nowTime;
        LocalTime startTime;
        LocalTime endTime;
        try {
            nowTime = now.toLocalTime();
            startTime = LocalTime.parse(shiftStart);
            endTime = LocalTime.parse(shiftEnd);
        } catch (Exception ex) {
            return true;
        }
        if (nowTime.isBefore(startTime) || !nowTime.isBefore(endTime)) {
            return true;
        }
        List<CalendarBreak> breaks = calendar.getBreaks();
        if (CollectionUtils.isEmpty(breaks)) {
            return false;
        }
        for (CalendarBreak item : breaks) {
            if (item == null || StringUtils.isEmpty(item.getStart()) || StringUtils.isEmpty(item.getEnd())) {
                continue;
            }
            try {
                LocalTime breakStart = LocalTime.parse(item.getStart());
                LocalTime breakEnd = LocalTime.parse(item.getEnd());
                if (!nowTime.isBefore(breakStart) && nowTime.isBefore(breakEnd)) {
                    return true;
                }
            } catch (Exception ex) {
                return true;
            }
        }
        return false;
    }

    private boolean isWorkday(String workdayPattern, DayOfWeek dayOfWeek) {
        if (StringUtils.isEmpty(workdayPattern) || dayOfWeek == null) {
            return true;
        }
        String normalized = workdayPattern.replace(" ", "");
        if (normalized.contains(",")) {
            String[] tokens = normalized.split(",");
            for (String token : tokens) {
                if (matchesDayToken(token, dayOfWeek)) {
                    return true;
                }
            }
            return false;
        }
        if (normalized.contains("-")) {
            String[] range = normalized.split("-");
            if (range.length != 2) {
                return false;
            }
            DayOfWeek start = parseDayOfWeek(range[0]);
            DayOfWeek end = parseDayOfWeek(range[1]);
            if (start == null || end == null) {
                return false;
            }
            int startValue = start.getValue();
            int endValue = end.getValue();
            int dayValue = dayOfWeek.getValue();
            if (startValue <= endValue) {
                return dayValue >= startValue && dayValue <= endValue;
            }
            return dayValue >= startValue || dayValue <= endValue;
        }
        return matchesDayToken(normalized, dayOfWeek);
    }

    private boolean matchesDayToken(String token, DayOfWeek dayOfWeek) {
        DayOfWeek parsed = parseDayOfWeek(token);
        return parsed != null && parsed.equals(dayOfWeek);
    }

    private DayOfWeek parseDayOfWeek(String token) {
        if (StringUtils.isEmpty(token)) {
            return null;
        }
        String key = token.trim().toLowerCase();
        switch (key) {
            case "mon":
            case "monday":
            case "1":
                return DayOfWeek.MONDAY;
            case "tue":
            case "tues":
            case "tuesday":
            case "2":
                return DayOfWeek.TUESDAY;
            case "wed":
            case "wednesday":
            case "3":
                return DayOfWeek.WEDNESDAY;
            case "thu":
            case "thur":
            case "thurs":
            case "thursday":
            case "4":
                return DayOfWeek.THURSDAY;
            case "fri":
            case "friday":
            case "5":
                return DayOfWeek.FRIDAY;
            case "sat":
            case "saturday":
            case "6":
                return DayOfWeek.SATURDAY;
            case "sun":
            case "sunday":
            case "7":
                return DayOfWeek.SUNDAY;
            default:
                return null;
        }
    }

    private String buildBizId(Object entity) {
        BizIdPrefix annotation = entity.getClass().getAnnotation(BizIdPrefix.class);
        String prefix = annotation != null ? annotation.value() : null;
        String suffix = IdUtils.simpleUUID();
        return StringUtils.isNotEmpty(prefix) ? prefix + suffix : suffix;
    }
}
