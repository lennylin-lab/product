package com.product.identity.system.service;

/**
 * 用户服务接口
 *
 * @author travel
 * @version 1.0
 * @since 2025-12-15
 *
 * 功能描述：
 * 提供用户管理相关的业务逻辑接口，包括：
 * - 用户基本信息的增删改查操作
 * - 用户认证和授权管理
 * - 用户密码管理
 * - 用户角色和权限分配
 * - 用户状态管理
 * - 用户数据权限控制
 *
 * 主要方法：
 * - selectUserList: 分页查询用户列表
 * - selectUserByLoginName: 根据登录名查询用户
 * - insertUser: 新增用户
 * - updateUser: 修改用户信息
 * - deleteUserById: 删除用户
 * - resetUserPwd: 重置用户密码
 * - changeStatus: 修改用户状态
 * - authRole: 用户角色授权
 *
 * 安全特性：
 * - 密码加密存储
 * - 用户状态锁定机制
 * - 登录失败次数限制
 * - 数据权限过滤
 * - 操作审计日志
 */

import com.baomidou.mybatisplus.extension.service.IService;
import com.product.identity.domain.entity.SysUser;

/**
 * 用户 业务层
 *
 * @author fast
 */
public interface ISysUserService extends IService<SysUser>
{
    public SysUser selectUserByUserName(String userName);

    boolean updateUserProfile(SysUser sysUser);

    SysUser selectUserById(Long userId);

    Object selectUserRoleGroup(String userName);

    boolean resetUserPwd(String userName, String newPassword);

    boolean checkPhoneUnique(SysUser user);

    boolean checkEmailUnique(SysUser user);

    boolean updateUserAvatar(String userName, String avatar);

    SysUser selectUserByUserId(Long userId);
}
