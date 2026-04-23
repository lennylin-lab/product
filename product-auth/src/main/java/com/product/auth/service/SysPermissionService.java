package com.product.auth.service;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.domain.entity.*;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @Auther: chuan
 * @Date: 2025/12/19 - 12 - 19 - 17:37
 * @Description: com.product.service
 * @version: 1.0
 */
@Component
public class SysPermissionService {
    /**
     * @param sysUser
     * @return String
     * @description 获取角色权限信息
     */
    public Set<String> getRolePermission(SysUser sysUser) {
        Set<String> roles = new HashSet<>();
        // 管理员拥有所有权限
        if (sysUser.isAdmin()) {
            roles.add("admin");
        } else {
            Set<Long> roleIds = Db.lambdaQuery(SysUserRole.class)
                    .select(SysUserRole::getRoleId)
                    .eq(SysUserRole::getUserId, sysUser.getUserId())
                    .list()
                    .stream()
                    .map(SysUserRole::getRoleId)
                    .collect(Collectors.toSet());
            roles = Db.lambdaQuery(SysRole.class)
                    .select(SysRole::getRoleKey)
                    .in(SysRole::getRoleId, roleIds)
                    .list()
                    .stream()
                    .map(SysRole::getRoleKey)
                    .collect(Collectors.toSet());
        }
        return roles;
    }

    /**
     * @param sysUser
     * @return String
     * @description 获取菜单权限信息
     */
    public Set<String> getMenuPermission(SysUser sysUser) {
        Set<String> perms = new HashSet<>();
        if (sysUser.isAdmin()) {
            perms.add("*:*:*");
        } else {
            Long[] roleIds = sysUser.getRoleIds();
            if (roleIds == null || roleIds.length == 0) {
                return perms;
            }
            Set<Long> menus = Db.lambdaQuery(SysRoleMenu.class)
                    .select(SysRoleMenu::getMenuId)
                    .in(SysRoleMenu::getRoleId, Arrays.asList(roleIds))
                    .list()
                    .stream()
                    .map(SysRoleMenu::getMenuId)
                    .collect(Collectors.toSet());
            perms = Db.lambdaQuery(SysMenu.class)
                    .select(SysMenu::getPerms)
                    .in(SysMenu::getMenuId, menus)
                    .list()
                    .stream()
                    .map(SysMenu::getPerms)
                    .collect(Collectors.toSet());
        }
        return perms;
    }
}
