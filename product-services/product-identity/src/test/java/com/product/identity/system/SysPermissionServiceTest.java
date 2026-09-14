package com.product.identity.system;

import com.product.identity.auth.service.SysPermissionService;
import com.product.identity.domain.entity.SysUser;
import com.product.identity.system.mapper.SysMenuMapper;
import com.product.identity.system.mapper.SysRoleMapper;
import com.product.identity.system.mapper.SysRoleMenuMapper;
import com.product.identity.system.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 权限查询单测：管理员返回 admin 角色与全量权限（与单体一致）；
 * 普通用户角色来自 sys_user_role 到 sys_role 的关联。
 */
class SysPermissionServiceTest {

    private final SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
    private final SysRoleMapper roleMapper = mock(SysRoleMapper.class);
    private final SysRoleMenuMapper roleMenuMapper = mock(SysRoleMenuMapper.class);
    private final SysMenuMapper menuMapper = mock(SysMenuMapper.class);

    @org.junit.jupiter.api.BeforeEach
    void initMybatisPlusEntityCache() {
        // MP lambda 查询依赖 TableInfo 缓存；纯单测环境需手动初始化
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                com.product.identity.domain.entity.SysUserRole.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                com.product.identity.domain.entity.SysRole.class);
    }

    private SysPermissionService service() {
        return new SysPermissionService(userRoleMapper, roleMapper, roleMenuMapper, menuMapper);
    }

    @Test
    void adminShouldGetAdminRoleAndAllPermission() {
        SysUser admin = new SysUser();
        admin.setUserId(1L);
        assertEquals(Set.of("admin"), service().getRolePermission(admin));
        assertEquals(Set.of("*:*:*"), service().getMenuPermission(admin));
    }

    @Test
    void normalUserShouldResolveRolesFromUserRoleTables() {
        SysUser user = new SysUser();
        user.setUserId(2L);
        com.product.identity.domain.entity.SysUserRole ur = new com.product.identity.domain.entity.SysUserRole();
        ur.setUserId(2L);
        ur.setRoleId(2L);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(ur));
        com.product.identity.domain.entity.SysRole role = new com.product.identity.domain.entity.SysRole();
        role.setRoleKey("common");
        when(roleMapper.selectList(any())).thenReturn(List.of(role));

        assertEquals(Set.of("common"), service().getRolePermission(user));
    }

    @Test
    void normalUserWithoutRoleIdsShouldHaveEmptyMenuPermission() {
        SysUser user = new SysUser();
        user.setUserId(2L);
        // 单体 getInfo 链路中 selectUserByUserId 不填充 roleIds → 菜单权限为空集（冻结行为）
        assertEquals(Set.of(), service().getMenuPermission(user));
    }
}
