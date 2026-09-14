package com.product.identity.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.identity.domain.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @Auther: chuan
 * @Date: 2025/12/19 - 12 - 19 - 17:16
 * @Description: com.product.mapper
 * @version: 1.0
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {
    List<SysRole> selectRoleList(SysRole sysRole);

    List<SysRole> selectRolesByUserName(String userName);

    SysRole selectRoleById(Long roleId);
}
