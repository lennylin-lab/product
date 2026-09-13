package com.product.pps.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.cache.constants.RedisLockKeys;
import com.product.common.constant.StatusConstants;
import com.product.common.exception.ServiceException;
import com.product.common.utils.StringUtils;
import com.product.cache.lock.RedisDistributedLock;
import com.product.domain.dto.TaskAssignmentDTO;
import com.product.domain.entity.ScheduleJob;
import com.product.pps.dto.ScheduleExecutionResult;
import com.product.pps.dto.ScheduleProgressDTO;
import com.product.pps.mapper.ScheduleJobMapper;
import com.product.pps.service.IScheduleJobService;
import com.product.pps.service.ITaskAssignmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 排程任务记录Service业务层处理
 *
 * 职责：
 * - 管理异步排程任务的生命周期（创建、执行、更新、完成）
 * - 提供排程任务查询功能（单个查询、分页查询）
 * - 协调线程池执行排程任务
 * - 实时更新排程进度到数据库
 * - 处理排程任务的成功/失败状态
 *
 * 核心流程：
 * 1. 创建排程任务（PENDING 状态）
 * 2. 提交到线程池异步执行
 * 3. 后台线程更新状态为 RUNNING
 * 4. 执行排程并实时更新进度
 * 5. 根据执行结果更新为 SUCCESS/FAILED
 *
 * 状态流转：
 * PENDING（待执行） → RUNNING（执行中） → SUCCESS（成功）/ FAILED（失败）
 *
 * 防重机制：
 * - 同一时间只允许一个 PENDING/RUNNING 状态的全量排程任务
 * - 避免并发排程互相覆盖结果
 */
@Slf4j
@Service
public class ScheduleJobServiceImpl implements IScheduleJobService {
    @Autowired
    private ScheduleJobMapper scheduleJobMapper;
    @Autowired
    @Qualifier("threadPoolTaskExecutor")
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;
    @Autowired
    private ITaskAssignmentService taskAssignmentService;
    @Autowired
    private RedisDistributedLock redisDistributedLock;

    /**
     * 异步全量排程入口
     *
     * 功能：创建异步排程任务并立即返回 jobId，供前端轮询查询进度
     *
     * 执行流程：
     * 1. 防重检查：查询是否有 PENDING/RUNNING 状态的排程任务
     * 2. 创建 ScheduleJob 记录（状态为 PENDING）
     * 3. 提交到线程池异步执行
     * 4. 立即返回 jobId 供前端查询进度
     *
     * 防重机制：
     * - 同一时间只允许一个异步排程任务运行
     * - 避免多个后台任务同时竞争同一批 READY 任务
     * - 保证排程结果的唯一性和一致性
     *
     * 异步特性：
     * - 立即返回 jobId，不阻塞等待排程完成
     * - 支持通过 jobId 查询排程进度和结果
     * - 适合大批量任务的排程场景
     *
     * 使用示例：
     * <pre>{@code
     * // 前端调用
     * Long jobId = scheduleJobService.scheduleAllAsync(dto);
     * // 立即返回：jobId = "abc123"
     *
     * // 前端轮询进度
     * ScheduleJob job = scheduleJobService.selectScheduleJobByJobId(jobId);
     * // job.getStatus() = "RUNNING"
     * // job.getProgressPercent() = 45
     * // job.getProcessedTaskCount() = 450
     *
     * // 10秒后再次查询
     * job = scheduleJobService.selectScheduleJobByJobId(jobId);
     * // job.getStatus() = "SUCCESS"
     * // job.getProgressPercent() = 100
     * }</pre>
     *
     * @param taskAssignmentDTO 排程参数（可指定排程开始时间）
     * @return 排程任务ID（用于查询进度）
     * @throws ServiceException 如果已有排程任务在执行
     */
    @Override
    public Long scheduleAllAsync(TaskAssignmentDTO taskAssignmentDTO) {
        // ========== 入口互斥 ==========
        // 先拿分布式锁，再做"查重 + 创建 job + 提交线程"的临界区操作。
        // 这里锁的持有时间很短，主要是防止多个请求同时穿透到 count() 之前。
        boolean locked = redisDistributedLock.tryLock(RedisLockKeys.Pps.Schedule.SUBMIT_LOCK_KEY, 0, 30, TimeUnit.SECONDS);
        if (!locked) {
            throw new ServiceException("当前已有排程任务在执行，请稍后再试");
        }
        try {
            // ========== 防重检查 ==========
            // 同一时间只允许一个全量排程任务处于 PENDING/RUNNING
            // 锁只是第一道闸，数据库状态是第二道闸
            long runningJobs = Db.lambdaQuery(ScheduleJob.class)
                    .in(ScheduleJob::getStatus, StatusConstants.PENDING_SCHEDULE_JOB, StatusConstants.RUNNING_SCHEDULE_JOB)
                    .count();
            if (runningJobs > 0) {
                throw new ServiceException("当前已有排程任务在执行，请稍后再试");
            }

            // ========== 创建排程任务记录 ==========
            ScheduleJob scheduleJob = new ScheduleJob();
            scheduleJob.setJobType("FULL");  // 全量排程类型
            scheduleJob.setStatus(StatusConstants.PENDING_SCHEDULE_JOB);  // 初始状态为待执行
            scheduleJob.setAssignmentStart(taskAssignmentDTO == null ? null : taskAssignmentDTO.getAssignmentStart());
            scheduleJob.setTotalTaskCount(0);
            scheduleJob.setScheduledTaskCount(0);
            scheduleJob.setProcessedTaskCount(0);
            scheduleJob.setProgressPercent(0);
            int inserted = scheduleJobMapper.insert(scheduleJob);
            // 防御性编程
            if (inserted != 1) {
                throw new ServiceException("创建排程任务失败");
            }

            // ========== 提交异步任务 ==========
            // 创建记录后立即异步投递，接口本身只负责"提交任务"，不阻塞等待排程结束
            try {
                threadPoolTaskExecutor.execute(() -> executeScheduleJob(scheduleJob.getJobId(), taskAssignmentDTO));
            } catch (Exception ex) {
                // 线程池拒绝或提交失败时，直接把 job 标记为失败，避免留下 PENDING 僵尸记录
                markScheduleJobFailed(scheduleJob.getJobId(), ex.getMessage());
                throw new ServiceException("提交排程任务失败");
            }
            return scheduleJob.getJobId();
        } finally {
            redisDistributedLock.unlock(RedisLockKeys.Pps.Schedule.SUBMIT_LOCK_KEY);
        }
    }

    /**
     * 根据任务ID查询排程任务
     *
     * 用途：前端轮询查询排程进度和结果
     *
     * @param jobId 排程任务ID
     * @return 排程任务信息（包含状态、进度、结果等）
     */
    @Override
    public ScheduleJob selectScheduleJobByJobId(Long jobId) {
        return scheduleJobMapper.selectById(jobId);
    }

    /**
     * 分页查询排程任务列表
     *
     * 用途：管理界面展示排程任务历史记录
     *
     * @param page         分页参数
     * @param scheduleJob 查询条件（可选）
     * @return 分页结果
     */
    @Override
    public Page<ScheduleJob> selectScheduleJobPage(Page<ScheduleJob> page, ScheduleJob scheduleJob) {
        return scheduleJobMapper.selectPage(page, buildScheduleJobQueryWrapper(scheduleJob));
    }

    /**
     * 后台线程执行排程任务
     *
     * 执行流程：
     * 1. 将任务状态从 PENDING 更新为 RUNNING（记录开始时间）
     * 2. 调用 TaskAssignmentService 执行实际排程
     * 3. 通过 Consumer 回调实时更新进度到数据库
     * 4. 根据执行结果更新任务状态为 SUCCESS 或 FAILED
     *
     * 状态流转：
     * PENDING → RUNNING → SUCCESS/FAILED
     *
     * 进度更新机制：
     * - 每批任务计算完成后，调用 updateScheduleJobProgress() 更新进度
     * - 前端可以通过 jobId 轮询查询最新进度
     * - 支持展示进度条、已处理任务数、批次数等信息
     *
     * 异常处理：
     * - 捕获所有异常，避免线程池中的异常导致任务状态无法更新
     * - 异常信息记录到日志和数据库
     * - 标记任务状态为 FAILED
     *
     * @param jobId              排程任务ID
     * @param taskAssignmentDTO 排程参数
     */
    private void executeScheduleJob(Long jobId, TaskAssignmentDTO taskAssignmentDTO) {
        // ========== 状态流转：PENDING → RUNNING ==========
        // 后台线程真正执行排程前，先把任务标记为 RUNNING
        // 便于前端轮询查看进度
        boolean runningUpdated = Db.lambdaUpdate(ScheduleJob.class)
                .set(ScheduleJob::getStatus, StatusConstants.RUNNING_SCHEDULE_JOB)
                .set(ScheduleJob::getStartedAt, LocalDateTime.now())
                .eq(ScheduleJob::getJobId, jobId)
                .eq(ScheduleJob::getStatus, StatusConstants.PENDING_SCHEDULE_JOB)
                .update();
        if (!runningUpdated) {
            log.warn("schedule job skipped because status is not PENDING, jobId={}", jobId);
            return;
        }

        try {
            // ========== 执行排程（带进度回调）==========
            // 真正的排程计算复用 TaskAssignmentService 的同步能力
            // 通过 Consumer 回调实时更新进度到数据库
            ScheduleExecutionResult result = taskAssignmentService.executeSchedulePlan(taskAssignmentDTO, progress -> updateScheduleJobProgress(jobId, progress));

            if (result.isSuccess()) {
                // ========== 状态流转：RUNNING → SUCCESS ==========
                // 成功时记录批次数、任务数和完成时间，供前端展示和后续审计
                boolean successUpdated = Db.lambdaUpdate(ScheduleJob.class)
                        .set(ScheduleJob::getStatus, StatusConstants.SUCCESS_SCHEDULE_JOB)
                        .set(ScheduleJob::getFinishedAt, LocalDateTime.now())
                        .set(ScheduleJob::getTotalTaskCount, result.getTotalTaskCount())
                        .set(ScheduleJob::getBatchCount, result.getBatchCount())
                        .set(ScheduleJob::getScheduledTaskCount, result.getTotalTaskCount())
                        .set(ScheduleJob::getProcessedTaskCount, result.getTotalTaskCount())
                        .set(ScheduleJob::getProgressPercent, 100)
                        .eq(ScheduleJob::getJobId, jobId)
                        .eq(ScheduleJob::getStatus, StatusConstants.RUNNING_SCHEDULE_JOB)
                        .update();
                if (!successUpdated) {
                    log.warn("schedule job success update skipped because status already changed, jobId={}", jobId);
                }
                return;
            }
            // 排程逻辑失败，标记任务失败
            markScheduleJobFailed(jobId, result.getErrorMessage());
        } catch (Exception ex) {
            // 异常处理：记录日志并标记任务失败
            log.error("schedule job failed, jobId={}", jobId, ex);
            markScheduleJobFailed(jobId, ex.getMessage());
        }
    }

    /**
     * 更新排程任务进度
     *
     * 调用时机：每批任务计算完成后
     *
     * 更新内容：
     * - totalTaskCount: 总任务数
     * - processedTaskCount: 已处理任务数
     * - batchCount: 已完成批次数
     * - progressPercent: 进度百分比（0-100）
     *
     * 作用：前端通过 jobId 轮询获取最新进度，展示进度条和统计信息
     *
     * @param jobId   排程任务ID
     * @param progress 进度数据（由 TaskAssignmentService 推送）
     */
    private void updateScheduleJobProgress(Long jobId, ScheduleProgressDTO progress) {
        if (jobId == null || progress == null) {
            return;
        }
        Db.lambdaUpdate(ScheduleJob.class)
                .set(ScheduleJob::getTotalTaskCount, progress.getTotalTaskCount())
                .set(ScheduleJob::getProcessedTaskCount, progress.getProcessedTaskCount())
                .set(ScheduleJob::getBatchCount, progress.getBatchCount())
                .set(ScheduleJob::getProgressPercent, progress.getProgressPercent())
                .eq(ScheduleJob::getJobId, jobId)
                .eq(ScheduleJob::getStatus, StatusConstants.RUNNING_SCHEDULE_JOB)
                .update();
    }

    /**
     * 标记排程任务为失败状态
     *
     * 状态流转：RUNNING → FAILED
     *
     * 失败信息处理：
     * - 兜底处理：如果错误信息为空，使用"未知异常"
     * - 截断处理：错误信息最长500字符（数据库字段长度限制）
     * - 记录时间：设置完成时间为当前时间
     *
     * 为什么需要截断？
     * - 数据库 error_message 字段长度为 VARCHAR(500)
     * - 避免超长错误信息导致数据库插入失败
     * - 保留关键错误信息的前500字符通常足够定位问题
     *
     * @param jobId       排程任务ID
     * @param errorMessage 错误信息
     */
    private void markScheduleJobFailed(Long jobId, String errorMessage) {
        // ========== 错误信息处理 ==========
        // 兜底：防止空指针异常
        // 截断：避免数据库字段长度溢出
        String safeErrorMessage = errorMessage;
        if (StringUtils.isEmpty(safeErrorMessage)) {
            safeErrorMessage = "未知异常";
        }
        if (safeErrorMessage.length() > 500) {
            safeErrorMessage = safeErrorMessage.substring(0, 500);
        }
        Db.lambdaUpdate(ScheduleJob.class)
                .set(ScheduleJob::getStatus, StatusConstants.FAILED_SCHEDULE_JOB)
                .set(ScheduleJob::getFinishedAt, LocalDateTime.now())
                .set(ScheduleJob::getErrorMessage, safeErrorMessage)
                .eq(ScheduleJob::getJobId, jobId)
                .in(ScheduleJob::getStatus, StatusConstants.PENDING_SCHEDULE_JOB, StatusConstants.RUNNING_SCHEDULE_JOB)
                .update();
    }


    /**
     * 构建排程任务查询条件
     *
     * 设计原则：
     * - 只拼接传入的过滤条件，避免无意义的全字段条件
     * - 减少 SQL 复杂度，提升查询性能
     * - 默认按创建时间和任务ID倒序排列（最新的在前）
     *
     * 支持的查询条件：
     * - jobId: 任务ID（精确匹配）
     * - jobType: 任务类型（精确匹配，如 "FULL"）
     * - status: 任务状态（精确匹配，如 "RUNNING"）
     * - assignmentStart: 排程开始时间（精确匹配）
     * - startedAt: 开始时间（范围查询：>= 指定时间）
     * - finishedAt: 完成时间（范围查询：<= 指定时间）
     *
     * @param scheduleJob 查询条件（可为 null）
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<ScheduleJob> buildScheduleJobQueryWrapper(ScheduleJob scheduleJob) {
        LambdaQueryWrapper<ScheduleJob> wrapper = new LambdaQueryWrapper<>();
        if (scheduleJob == null) {
            // 无查询条件时，默认按创建时间和任务ID倒序
            return wrapper.orderByDesc(ScheduleJob::getCreateTime, ScheduleJob::getJobId);
        }
        // 只拼接非空的条件，避免 SQL 中出现无意义的 IS NULL 条件
        wrapper.eq(scheduleJob.getJobId() != null, ScheduleJob::getJobId, scheduleJob.getJobId());
        wrapper.eq(StringUtils.isNotEmpty(scheduleJob.getJobType()), ScheduleJob::getJobType, scheduleJob.getJobType());
        wrapper.eq(StringUtils.isNotEmpty(scheduleJob.getStatus()), ScheduleJob::getStatus, scheduleJob.getStatus());
        wrapper.eq(scheduleJob.getAssignmentStart() != null, ScheduleJob::getAssignmentStart, scheduleJob.getAssignmentStart());
        wrapper.ge(scheduleJob.getStartedAt() != null, ScheduleJob::getStartedAt, scheduleJob.getStartedAt());
        wrapper.le(scheduleJob.getFinishedAt() != null, ScheduleJob::getFinishedAt, scheduleJob.getFinishedAt());
        return wrapper.orderByDesc(ScheduleJob::getCreateTime, ScheduleJob::getJobId);
    }
}
