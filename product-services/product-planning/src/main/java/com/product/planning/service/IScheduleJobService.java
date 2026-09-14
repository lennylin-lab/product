package com.product.planning.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.planning.domain.dto.TaskAssignmentDTO;
import com.product.planning.domain.entity.ScheduleJob;

/**
 * 排程任务记录Service接口
 */
public interface IScheduleJobService {
    Long scheduleAllAsync(TaskAssignmentDTO taskAssignmentDTO);

    ScheduleJob selectScheduleJobByJobId(Long jobId);

    Page<ScheduleJob> selectScheduleJobPage(Page<ScheduleJob> page, ScheduleJob scheduleJob);
}
