package com.product.identity.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.product.identity.domain.entity.SysMenu;
import com.product.identity.domain.entity.SysRole;
import com.product.identity.domain.entity.SysRoleMenu;
import com.product.identity.domain.entity.SysUser;
import com.product.identity.domain.entity.SysUserRole;
import com.product.identity.system.mapper.SysMenuMapper;
import com.product.identity.system.mapper.SysRoleMapper;
import com.product.identity.system.mapper.SysRoleMenuMapper;
import com.product.identity.system.mapper.SysUserRoleMapper;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 角色与菜单权限查询（与单体 SysPermissionService 行为一致）。
 *
 * <p>差异说明：单体用 MyBatis-Plus {@code Db.lambdaQuery} 工具链；identity 改为显式
 * mapper 查询（同库同语义），返回集合内容一致。管理员（userId=1）返回
 * 角色集合 {admin} / 权限集合 {*:*:*}；普通用户角色来自 sys_user_role→sys_role，
 * 菜单权限走 roleIds（单体 getInfo 链路中 selectUserByUserId 不填充 roleIds，
 * 因此普通用户菜单权限为空集——行为冻结保持）。</p>
 */
@Component
public class SysPermissionService {

    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysMenuMapper menuMapper;

    public SysPermissionService(SysUserRoleMapper userRoleMapper,
                                SysRoleMapper roleMapper,
                                SysRoleMenuMapper roleMenuMapper,
                                SysMenuMapper menuMapper) {
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.menuMapper = menuMapper;
    }

    /**
     * 获取角色权限信息
     */
    public Set<String> getRolePermission(SysUser sysUser) {
        Set<String> roles = new HashSet<>();
        // 管理员拥有所有权限
        if (sysUser.isAdmin()) {
            roles.add("admin");
        } else {
            Set<Long> roleIds = userRoleMapper.selectList(
                            Wrappers.<SysUserRole>lambdaQuery()
                                    .select(SysUserRole::getRoleId)
                                    .eq(SysUserRole::getUserId, sysUser.getUserId()))
                    .stream().map(SysUserRole::getRoleId).collect(Collectors.toSet());
            if (roleIds.isEmpty()) {
                return roles;
            }
            roles = roleMapper.selectList(
                            Wrappers.<SysRole>lambdaQuery()
                                    .select(SysRole::getRoleKey)
                                    .in(SysRole::getRoleId, roleIds))
                    .stream().map(SysRole::getRoleKey).collect(Collectors.toSet());
        }
        return roles;
    }

    /**
     * 获取菜单权限信息
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
            Set<Long> menus = roleMenuMapper.selectList(
                            Wrappers.<SysRoleMenu>lambdaQuery()
                                    .select(SysRoleMenu::getMenuId)
                                    .in(SysRoleMenu::getRoleId, Arrays.asList(roleIds)))
                    .stream().map(SysRoleMenu::getMenuId).collect(Collectors.toSet());
            if (menus.isEmpty()) {
                return perms;
            }
            perms = menuMapper.selectList(
                            Wrappers.<SysMenu>lambdaQuery()
                                    .select(SysMenu::getPerms)
                                    .in(SysMenu::getMenuId, menus))
                    .stream().map(SysMenu::getPerms).collect(Collectors.toSet());
        }
        return perms;
    }
}
