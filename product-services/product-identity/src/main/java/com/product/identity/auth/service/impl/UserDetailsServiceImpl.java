package com.product.identity.auth.service.impl;

import com.product.identity.auth.domain.LoginPrincipal;
import com.product.identity.auth.service.SysPasswordService;
import com.product.identity.common.constant.Constants;
import com.product.identity.common.enums.UserStatus;
import com.product.cloud.common.exception.ServiceException;
import com.product.identity.common.utils.MessageUtils;
import com.product.identity.common.utils.StringUtils;
import com.product.identity.domain.entity.SysUser;
import com.product.identity.system.service.ISysUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 用户验证处理类（单体 UserDetailsServiceImpl 的 identity 移植，异常与消息逐一对应）。
 *
 * <p>差异说明：单体返回 {@code LoginUser}（UserDetails 实现），identity 返回行为一致的
 * {@link LoginPrincipal}；权限集合语义保持：仅超级管理员授予 {@code *:*:*}，其余为空集合
 * （因此 token claims 中的 permissions 与单体登录完全一致）。</p>
 */
@Slf4j
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    @Autowired
    private ISysUserService userService;

    @Autowired
    private SysPasswordService passwordService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 查询系统用户
        SysUser user = userService.selectUserByUserName(username);
        log.info("查询的用户信息: {}", user);
        if (StringUtils.isNull(user)) {
            log.info("登录用户：{} 不存在.", username);
            throw new ServiceException(MessageUtils.message("user.not.exists"));
        } else if (UserStatus.DELETED.getCode().equals(user.getDelFlag())) {
            log.info("登录用户：{} 已被删除.", username);
            throw new ServiceException(MessageUtils.message("user.password.delete"));
        } else if (UserStatus.DISABLE.getCode().equals(user.getStatus())) {
            log.info("登录用户：{} 已被停用.", username);
            throw new UsernameNotFoundException("对不起，您的账号：" + username + " 已被停用");
        }

        passwordService.validate(user);
        return createLoginUser(user);
    }

    public UserDetails createLoginUser(SysUser user) {
        Set<String> permissions = new HashSet<>();
        // 超级管理员直接授予全量权限标识，避免空权限导致拒绝访问（与单体一致）
        if (SysUser.isAdmin(user.getUserId())) {
            permissions.add(Constants.ALL_PERMISSION);
        }
        return new LoginPrincipal(user, permissions);
    }
}
