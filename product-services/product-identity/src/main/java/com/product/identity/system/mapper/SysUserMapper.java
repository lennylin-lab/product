package com.product.identity.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.identity.domain.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Auther: chuan
 * @Date: 2025/12/18 - 12 - 18 - 23:03
 * @Description: com.product.mapper
 * @version: 1.0
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    public SysUser selectUserByUserName(String userName);

    SysUser selectUserByUserId(Long userId);
}
