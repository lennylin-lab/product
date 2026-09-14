package com.product.planning.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * @Auther: chuan
 * @Date: 2026/1/1 - 01 - 01 - 02:10
 * @Description: com.product.planning.domain.entity
 * @version: 1.0
 */
@Data
@TableName("task_dependency")
public class TaskDependency {
    @TableField("pre_task_id")
    private Long preTaskId;

    @TableField("post_task_id")
    private Long postTaskId;
}
