package com.product.pps.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.cache.constants.RedisLockKeys;
import com.product.cache.lock.RedisDistributedLock;
import com.product.common.constant.StatusConstants;
import com.product.common.utils.StringUtils;
import com.product.domain.dto.TaskAssignmentDTO;
import com.product.domain.entity.Calendar;
import com.product.domain.entity.OperationTask;
import com.product.domain.entity.Resource;
import com.product.domain.entity.ScheduleJob;
import com.product.domain.entity.TaskAssignment;
import com.product.domain.vo.TaskAssignmentVO;
import com.product.pps.dto.ScheduleExecutionResult;
import com.product.pps.dto.ScheduleProgressDTO;
import com.product.pps.enums.SchedulingStrategy;
import com.product.pps.mapper.TaskAssignmentMapper;
import com.product.pps.service.IScheduleJobService;
import com.product.pps.service.ITaskAssignmentService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Consumer;

/**
 * 派工/排程结果Service业务层处理（MyBatis-Plus）
 *
 * @author product
 * @date 2026-01-02
 */
@Slf4j
@Service
public class TaskAssignmentServiceImpl extends ServiceImpl<TaskAssignmentMapper, TaskAssignment>
        implements ITaskAssignmentService {
    // 采用配置的方式来设置按页加载的数量
    @Value("${product.pps.schedule.batch-size:200}")
    private int scheduleBatchSize;

    @Autowired
    private TaskAssignmentMapper taskAssignmentMapper;
    @Autowired
    @Lazy
    private IScheduleJobService scheduleJobService;
    @Autowired
    private TaskSchedulingCalculator taskSchedulingCalculator;
    @Autowired
    private TaskAssignmentPersistenceService taskAssignmentPersistenceService;
    @Autowired
    private TaskSchedulingQueryService taskSchedulingQueryService;
    @Autowired
    private TaskSchedulingCoordinator taskSchedulingCoordinator;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private RedisDistributedLock redisDistributedLock;

    /**
     * 查询派工/排程结果
     *
     * @param assignmentId 派工/排程结果主键
     * @return 派工/排程结果
     */
    @Override
    public TaskAssignment selectTaskAssignmentByAssignmentId(Long assignmentId) {
        return getById(assignmentId);
    }

    /**
     * 查询派工/排程结果列表
     *
     * @param taskAssignment 查询条件
     * @return 派工/排程结果集合
     */
    @Override
    public List<TaskAssignment> selectTaskAssignmentList(TaskAssignment taskAssignment) {
        return list(buildQueryWrapper(taskAssignment));
    }

    /**
     * 分页查询派工/排程结果列表
     *
     * @param page           分页参数
     * @param taskAssignment 查询条件
     * @return 分页结果
     */
    @Override
    public Page<TaskAssignmentVO> selectTaskAssignmentPage(Page<TaskAssignmentVO> page, TaskAssignment taskAssignment) {
        return taskAssignmentMapper.selectTaskAssignmentPage(page, taskAssignment);
    }

    /**
     * 新增派工/排程结果
     *
     * @param taskAssignment 派工/排程结果
     * @return 是否成功
     */
    @Override
    public boolean insertTaskAssignment(TaskAssignment taskAssignment) {
        boolean saved = save(taskAssignment);
        return saved;
    }

    /**
     * 批量新增派工/排程结果
     *
     * @param taskAssignments 派工/排程结果列表
     * @return 成功条数
     */
    @Override
    public int batchInsertTaskAssignment(List<TaskAssignment> taskAssignments) {
        if (CollectionUtils.isEmpty(taskAssignments)) {
            return 0;
        }
        boolean success = saveBatch(taskAssignments);
        return success ? taskAssignments.size() : 0;
    }

    /**
     * 修改派工/排程结果
     *
     * @param taskAssignment 派工/排程结果
     * @return 是否成功
     */
    @Override
    public boolean updateTaskAssignment(TaskAssignment taskAssignment) {
        boolean updated = updateById(taskAssignment);
        return updated;
    }

    /**
     * 批量删除派工/排程结果
     *
     * @param assignmentIds 主键集合
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTaskAssignmentByAssignmentIds(Long[] assignmentIds) {
        if (assignmentIds == null || assignmentIds.length == 0) {
            return false;
        }
        List<String> taskIds = lambdaQuery().select(TaskAssignment::getTaskId)
                .in(TaskAssignment::getAssignmentId, Arrays.asList(assignmentIds))
                .list()
                .stream()
                .map(TaskAssignment::getTaskId)
                .distinct()
                .collect(Collectors.toList());
        // 撤销排程
        Db.lambdaUpdate(OperationTask.class).set(OperationTask::getStatus, StatusConstants.READY_OPERATION_TASK)
                .in(OperationTask::getTaskId, taskIds)
                .update();
        return removeByIds(Arrays.asList(assignmentIds));
    }

    /**
     * 删除派工/排程结果信息
     *
     * @param assignmentId 主键
     * @return 是否成功
     */
    @Override
    public boolean deleteTaskAssignmentByAssignmentId(Long assignmentId) {
        return removeById(assignmentId);
    }

    /**
     * 单任务或全量排程入口
     *
     * 根据参数判断：
     * - 若指定了 taskId，则仅排程该任务
     * - 若未指定 taskId，则排程所有 READY 状态的任务（同步执行）
     *
     * @param taskAssignmentDTO 排程参数
     * @return 是否排程成功
     */
    @Override
    public boolean schedule(TaskAssignmentDTO taskAssignmentDTO) {
        if (taskAssignmentDTO == null || StringUtils.isEmpty(taskAssignmentDTO.getTaskId())) {
            return scheduleAll(taskAssignmentDTO);
        }

        OperationTask task = taskSchedulingQueryService.loadTaskByTaskId(taskAssignmentDTO.getTaskId());
        if (task == null) {
            return false;
        }
        if (!StatusConstants.READY_OPERATION_TASK.equals(task.getStatus())) {
            return false;
        }
        // 单任务也需要经过标准化，过滤已派工的任务，避免重复排程
        List<OperationTask> readyTasks = taskSchedulingQueryService.normalizeReadyTasksForScheduling(List.of(task));
        if (CollectionUtils.isEmpty(readyTasks)) {
            return false;
        }
        LocalDateTime assignmentStart = resolveAssignmentStart(taskAssignmentDTO);
        RLock lock = redisDistributedLock.getLock(RedisLockKeys.Pps.Schedule.EXECUTE_LOCK_KEY);
        lock.lock();
        try {
            SchedulingStrategy strategy = SchedulingStrategy.fromCode(taskAssignmentDTO.getScheduleStrategy());
            Boolean scheduled = transactionTemplate
                    .execute(status -> scheduleTasks(readyTasks, assignmentStart, strategy));
            return Boolean.TRUE.equals(scheduled);
        } finally {
            redisDistributedLock.unlock(lock);
        }
    }

    /**
     * 全量排程入口（同步）
     *
     * 将所有 READY 状态的任务分配到机台并生成派工记录
     * 注意：此方法会阻塞直到排程完成，适合任务量不大的场景
     *
     * @param taskAssignmentDTO 排程参数（可指定排程开始时间）
     * @return 是否排程成功
     */
    @Override
    public boolean scheduleAll(TaskAssignmentDTO taskAssignmentDTO) {
        return taskSchedulingCoordinator.executeSchedulePlan(taskAssignmentDTO).isSuccess();
    }

    @Override
    public ScheduleExecutionResult executeSchedulePlan(TaskAssignmentDTO taskAssignmentDTO,
            Consumer<ScheduleProgressDTO> progressConsumer) {
        RLock lock = redisDistributedLock.getLock(RedisLockKeys.Pps.Schedule.EXECUTE_LOCK_KEY);
        // 长临界区，使用lock()，Redission 会用 watchdog 自动续期
        lock.lock();
        try {
            return taskSchedulingCoordinator.executeSchedulePlan(taskAssignmentDTO, progressConsumer);
        } finally {
            redisDistributedLock.unlock(lock);
        }
    }

    @Override
    public String scheduleAllAsync(TaskAssignmentDTO taskAssignmentDTO) {
        return scheduleJobService.scheduleAllAsync(taskAssignmentDTO);
    }

    @Override
    public ScheduleJob selectScheduleJobByJobId(String jobId) {
        return scheduleJobService.selectScheduleJobByJobId(jobId);
    }

    @Override
    public Page<ScheduleJob> selectScheduleJobPage(Page<ScheduleJob> page, ScheduleJob scheduleJob) {
        return scheduleJobService.selectScheduleJobPage(page, scheduleJob);
    }

    /**
     * 构建查询条件
     */
    private LambdaQueryWrapper<TaskAssignment> buildQueryWrapper(TaskAssignment taskAssignment) {
        LambdaQueryWrapper<TaskAssignment> wrapper = new LambdaQueryWrapper<>();
        if (taskAssignment == null) {
            return wrapper;
        }
        wrapper.eq(taskAssignment.getTaskId() != null, TaskAssignment::getTaskId, taskAssignment.getTaskId());
        wrapper.eq(taskAssignment.getMachineId() != null, TaskAssignment::getMachineId, taskAssignment.getMachineId());
        wrapper.eq(taskAssignment.getPlannedStart() != null, TaskAssignment::getPlannedStart,
                taskAssignment.getPlannedStart());
        wrapper.eq(taskAssignment.getPlannedEnd() != null, TaskAssignment::getPlannedEnd,
                taskAssignment.getPlannedEnd());
        wrapper.eq(taskAssignment.getSequenceOnResource() != null, TaskAssignment::getSequenceOnResource,
                taskAssignment.getSequenceOnResource());
        return wrapper;
    }

    /**
     * 解析排程开始时间。
     *
     * 单任务排程仍然需要这个基准时间：
     * - 前端指定则优先使用前端值
     * - 否则使用当前时间
     */
    private LocalDateTime resolveAssignmentStart(TaskAssignmentDTO taskAssignmentDTO) {
        if (taskAssignmentDTO != null && taskAssignmentDTO.getAssignmentStart() != null) {
            return taskAssignmentDTO.getAssignmentStart();
        }
        return LocalDateTime.now();
    }

    /**
     * 排程指定任务列表
     *
     * 执行流程：
     * 1. 加载可用机台
     * 2. 加载机台关联的日历
     * 3. 构建机台运行时上下文
     * 4. 计算任务分配方案
     * 5. 持久化结果
     *
     * @param tasks           待排程的任务列表
     * @param assignmentStart 排程开始时间
     * @return 是否排程成功
     */
    private boolean scheduleTasks(List<OperationTask> tasks, LocalDateTime assignmentStart) {
        return scheduleTasks(tasks, assignmentStart, SchedulingStrategy.EARLIEST_START);
    }

    private boolean scheduleTasks(List<OperationTask> tasks, LocalDateTime assignmentStart,
            SchedulingStrategy strategy) {
        List<Resource> machines = taskSchedulingQueryService.loadAvailableMachines();
        if (CollectionUtils.isEmpty(machines)) {
            return false;
        }
        Map<Long, Calendar> calendarMap = taskSchedulingQueryService.loadCalendarMap(machines);
        TaskSchedulingCalculator.MachineRuntimeContext runtimeContext = taskSchedulingCalculator
                .buildMachineRuntimeContext(machines);
        TaskSchedulingCalculator.ScheduleBatchResult batchResult = taskSchedulingCalculator
                .calculateBatchAssignments(tasks, machines, calendarMap, runtimeContext, assignmentStart, strategy);
        return taskAssignmentPersistenceService.persistBatchResult(batchResult, scheduleBatchSize);
    }

}
