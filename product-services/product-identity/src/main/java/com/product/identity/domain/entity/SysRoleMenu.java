package com.product.identity.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * @Auther: chuan
 * @Date: 2025/12/19 - 12 - 19 - 17:15
 * @Description: com.product.entity
 * @version: 1.0
 */
@Data
@TableName("sys_role_menu")
public class SysRoleMenu {
    private Long roleId;
    private Long menuId;
}
