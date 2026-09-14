package com.product.identity.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.product.identity.common.constant.UserConstants;
import com.product.identity.domain.entity.SysRole;
import com.product.identity.domain.entity.SysUser;
import com.product.identity.system.mapper.SysRoleMapper;
import com.product.identity.system.mapper.SysUserMapper;
import com.product.identity.system.service.ISysUserService;
import com.product.identity.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Auther: chuan
 * @Date: 2025/12/18 - 12 - 18 - 12:27
 * @Description: com.product.service.impl
 * @version: 1.0
 */
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements ISysUserService {
    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SysRoleMapper roleMapper;

    @Override
    public SysUser selectUserByUserName(String userName) {
        return userMapper.selectUserByUserName(userName);
    }

    @Override
    public boolean updateUserProfile(SysUser sysUser) {

        return updateById(sysUser);
    }

    @Override
    public SysUser selectUserById(Long userId) {
        return userMapper.selectUserByUserId(userId);
    }

    @Override
    public Object selectUserRoleGroup(String userName) {
        List<SysRole> list = roleMapper.selectRolesByUserName(userName);
        if (CollectionUtils.isEmpty(list)) {
            return StringUtils.EMPTY;
        }
        return list.stream()
                .map(SysRole::getRoleName)
                .collect(Collectors.joining(","));
    }

    @Override
    public boolean resetUserPwd(String userName, String newPassword) {
        return lambdaUpdate().set(SysUser::getPassword, newPassword)
                .eq(SysUser::getUserName, userName)
                .update();
    }

    @Override
    public boolean checkPhoneUnique(SysUser user) {
        Long userId = StringUtils.isNull(user.getUserId()) ? -1L : user.getUserId();

        SysUser info = lambdaQuery().select(SysUser::getUserId, SysUser::getEmail)
                .eq(SysUser::getEmail, user.getEmail())
                .eq(SysUser::getDelFlag, 0)
                .last("limit 1")
                .one();
        if (StringUtils.isNotNull(info) && info.getUserId().longValue() != userId.longValue())
        {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }

    @Override
    public boolean checkEmailUnique(SysUser user) {
        Long userId = StringUtils.isNull(user.getUserId()) ? -1L : user.getUserId();
        SysUser info = lambdaQuery().select(SysUser::getUserId, SysUser::getUserName)
                .eq(SysUser::getUserName, user.getUserName())
                .eq(SysUser::getDelFlag, 0)
                .last("limit 1")
                .one();;
        if (StringUtils.isNotNull(info) && info.getUserId().longValue() != userId.longValue())
        {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }

    @Override
    public boolean updateUserAvatar(String userName, String avatar) {
        return lambdaUpdate().set(SysUser::getAvatar, avatar)
                .eq(SysUser::getUserName, userName)
                .update();
    }

    @Override
    public SysUser selectUserByUserId(Long userId) {
        return userMapper.selectUserByUserId(userId);
    }
}
