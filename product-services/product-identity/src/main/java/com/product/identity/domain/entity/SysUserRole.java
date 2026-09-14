package com.product.identity.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * @Auther: chuan
 * @Date: 2025/12/19 - 12 - 19 - 18:00
 * @Description: com.product.entity
 * @version: 1.0
 */
@Data
@TableName("sys_user_role")
public class SysUserRole {
    private Long userId;
    private Long roleId;
}
