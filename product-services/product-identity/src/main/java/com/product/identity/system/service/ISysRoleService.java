package com.product.identity.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.product.identity.domain.entity.SysRole;

import java.util.List;

/**
 * @Auther: chuan
 * @Date: 2025/12/19 - 12 - 19 - 17:17
 * @Description: com.product.service
 * @version: 1.0
 */
public interface ISysRoleService extends IService<SysRole> {
    List<SysRole> selectRoleAll();
}
