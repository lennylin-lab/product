package com.product.pps.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.domain.entity.OperationTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工序任务Mapper接口，基于 MyBatis-Plus
 *
 * @author product
 * @date 2025-12-31
 */
@Mapper
public interface OperationTaskMapper extends BaseMapper<OperationTask> {

    /**
     * 批量标记任务为已排程状态
     *
     * 用途：排程完成后批量更新任务状态
     * - 仅更新指定状态的任务（避免并发冲突）
     * - 乐观锁机制：WHERE status = #{fromStatus}
     *
     * 状态流转：READY -> SCHEDULED
     *
     * 并发安全性：
     * - 如果任务状态已被其他操作修改，updated 行数将不匹配
     * - 代码层会检测 updated 行数与 taskIds.size() 是否一致
     * - 不一致时抛出异常，触发事务回滚
     *
     * @param taskIds    任务ID列表
     * @param fromStatus 源状态（必须匹配此状态才会更新）
     * @param toStatus   目标状态
     * @return 实际更新的行数
     */
    int batchMarkScheduled(@Param("taskIds") List<Long> taskIds,
                           @Param("fromStatus") String fromStatus,
                           @Param("toStatus") String toStatus);
}
