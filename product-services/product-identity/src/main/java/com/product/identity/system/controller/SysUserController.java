package com.product.identity.system.controller;

/// 系统用户控制器
///
/// @author travel
/// @version 1.0
/// @since 2025-12-15
/// 功能描述：
/// 提供系统用户的增删改查功能，包括：
/// - 用户的分页查询
/// - 用户的导出功能
/// - 新增、修改、删除用户
/// - 用户密码重置
/// - 用户状态管理
/// - 用户角色分配
/// - 用户授权管理
/// - 查询用户已分配角色
/// - 取消用户角色授权
/// - 批量取消用户角色授权
/// - 导入用户数据
/// API路径：
/// - GET /system/user/list - 分页查询用户
/// - POST /system/user - 新增用户
/// - PUT /system/user - 修改用户
/// - GET /system/user/{userId} - 查询用户详情
/// - DELETE /system/user/{userIds} - 删除用户
/// - GET /system/user/export - 导出用户
/// - GET /system/user/{userId}/authRole - 查询用户角色
/// - PUT /system/user/authRole - 修改用户角色
/// - PUT /system/user/resetPwd/{userIds} - 重置用户密码
/// - PUT /system/user/changeStatus - 修改用户状态
/// - POST /system/user/importData - 导入用户数据
/// - GET /system/user/importTemplate - 下载导入模板
/// 安全特性：
/// - 密码加密存储
/// - 用户状态锁定
/// - 角色权限控制
/// - 数据权限过滤
/// - 操作日志记录
import com.product.identity.common.core.result.AjaxResult;
import com.product.identity.core.controller.BaseController;
import com.product.identity.domain.entity.SysRole;
import com.product.identity.domain.entity.SysUser;
import com.product.identity.system.service.ISysRoleService;
import com.product.identity.system.service.ISysUserService;
import com.product.identity.common.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户信息
 *
 * @author fast
 */
@Slf4j
@RestController
@RequestMapping("/system/user")
public class SysUserController extends BaseController
{
    @Autowired
    private ISysUserService userService;

    @Autowired
    private ISysRoleService roleService;

    /**
     * 获取用户列表
     */
/*     @PreAuthorize("@ss.hasPermi('system:user:list')")
    @GetMapping("/list")
    public TableDataInfo list(SysUser user)
    {
        startPage();
        List<SysUser> list = userService.selectUserList(user);
        return getDataTable(list);
    } */

/*     @PreAuthorize("@ss.hasPermi('system:user:export')")
    @PostMapping("/export")
    public void export(HttpServletResponse response, SysUser user)
    {
        List<SysUser> list = userService.selectUserList(user);
        ExcelUtil<SysUser> util = new ExcelUtil<SysUser>(SysUser.class);
        util.exportExcel(response, list, "用户数据");
    } */

/*     @PreAuthorize("@ss.hasPermi('system:user:import')")
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception
    {
        ExcelUtil<SysUser> util = new ExcelUtil<SysUser>(SysUser.class);
        List<SysUser> userList = util.importExcel(file.getInputStream());
        String operName = getUsername();
        String message = userService.importUser(userList, updateSupport, operName);
        return success(message);
    } */

/*     @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response)
    {
        ExcelUtil<SysUser> util = new ExcelUtil<SysUser>(SysUser.class);
        util.importTemplateExcel(response, "用户数据");
    } */

    /**
     * 根据用户编号获取详细信息
     *
     * <p>userId 限定纯数字（issue #1）：用户 CRUD 端点属冻结范围（与单体现状一致），
     * 不含 /list 等字面路径映射；无数字约束时字面路径会被 /{userId} 吞掉并以
     * 「参数类型不匹配」500 返回，现改落入统一 404 语义。</p>
     */
    @GetMapping(value = { "/", "/{userId:\\d+}" })
    public AjaxResult getInfo(@PathVariable(value = "userId", required = false) Long userId)
    {
        AjaxResult ajax = AjaxResult.success();
        if (StringUtils.isNotNull(userId))
        {
            SysUser sysUser = userService.selectUserById(userId);
            ajax.put(AjaxResult.DATA_TAG, sysUser);
            ajax.put("roleIds", sysUser.getRoles().stream().map(SysRole::getRoleId).collect(Collectors.toList()));
        }
        List<SysRole> roles = roleService.selectRoleAll();
        ajax.put("roles", SysUser.isAdmin(userId) ? roles : roles.stream().filter(r -> !r.isAdmin()).collect(Collectors.toList()));
        return ajax;
    }

    /**
     * 新增用户
     */
/*     @PreAuthorize("@ss.hasPermi('system:user:add')")
    @PostMapping
    public AjaxResult add(@Validated @RequestBody SysUser user)
    {
        roleService.checkRoleDataScope(user.getRoleIds());
        if (!userService.checkUserNameUnique(user))
        {
            return error("新增用户'" + user.getUserName() + "'失败，登录账号已存在");
        }
        else if (StringUtils.isNotEmpty(user.getPhonenumber()) && !userService.checkPhoneUnique(user))
        {
            return error("新增用户'" + user.getUserName() + "'失败，手机号码已存在");
        }
        else if (StringUtils.isNotEmpty(user.getEmail()) && !userService.checkEmailUnique(user))
        {
            return error("新增用户'" + user.getUserName() + "'失败，邮箱账号已存在");
        }
        user.setCreateBy(getUsername());
        user.setPassword(SecurityUtils.encryptPassword(user.getPassword()));
        return toAjax(userService.insertUser(user));
    } */

    /**
     * 修改用户
     */
/*     @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody SysUser user)
    {
        userService.checkUserAllowed(user);
        userService.checkUserDataScope(user.getUserId());
        roleService.checkRoleDataScope(user.getRoleIds());
        if (!userService.checkUserNameUnique(user))
        {
            return error("修改用户'" + user.getUserName() + "'失败，登录账号已存在");
        }
        else if (StringUtils.isNotEmpty(user.getPhonenumber()) && !userService.checkPhoneUnique(user))
        {
            return error("修改用户'" + user.getUserName() + "'失败，手机号码已存在");
        }
        else if (StringUtils.isNotEmpty(user.getEmail()) && !userService.checkEmailUnique(user))
        {
            return error("修改用户'" + user.getUserName() + "'失败，邮箱账号已存在");
        }
        user.setUpdateBy(getUsername());
        return toAjax(userService.updateUser(user));
    } */

    /**
     * 删除用户
     */
/*     @PreAuthorize("@ss.hasPermi('system:user:remove')")
    @DeleteMapping("/{userIds}")
    public AjaxResult remove(@PathVariable Long[] userIds)
    {
        if (ArrayUtils.contains(userIds, getUserId()))
        {
            return error("当前用户不能删除");
        }
        return toAjax(userService.deleteUserByIds(userIds));
    } */

    /**
     * 重置密码
     */
/*     @PreAuthorize("@ss.hasPermi('system:user:resetPwd')")
    @PutMapping("/resetPwd")
    public AjaxResult resetPwd(@RequestBody SysUser user)
    {
        userService.checkUserAllowed(user);
        userService.checkUserDataScope(user.getUserId());
        user.setPassword(SecurityUtils.encryptPassword(user.getPassword()));
        user.setUpdateBy(getUsername());
        return toAjax(userService.resetPwd(user));
    } */

    /**
     * 状态修改
     */
/*     @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @PutMapping("/changeStatus")
    public AjaxResult changeStatus(@RequestBody SysUser user)
    {
        userService.checkUserAllowed(user);
        userService.checkUserDataScope(user.getUserId());
        user.setUpdateBy(getUsername());
        return toAjax(userService.updateUserStatus(user));
    } */

    /**
     * 根据用户编号获取授权角色
     */
/*     @PreAuthorize("@ss.hasPermi('system:user:query')")
    @GetMapping("/authRole/{userId:\\d+}")
    public AjaxResult authRole(@PathVariable("userId") Long userId)
    {
        AjaxResult ajax = AjaxResult.success();
        SysUser user = userService.selectUserById(userId);
        List<SysRole> roles = roleService.selectRolesByUserId(userId);
        ajax.put("user", user);
        ajax.put("roles", SysUser.isAdmin(userId) ? roles : roles.stream().filter(r -> !r.isAdmin()).collect(Collectors.toList()));
        return ajax;
    } */

    /**
     * 用户授权角色
     */
/*     @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @PutMapping("/authRole")
    public AjaxResult insertAuthRole(Long userId, Long[] roleIds)
    {
        userService.checkUserDataScope(userId);
        roleService.checkRoleDataScope(roleIds);
        userService.insertUserAuth(userId, roleIds);
        return success();
    } */

    /**
     * 查询当前用户的账户余额
     */
/*     @GetMapping("/selectMyBalance")
    public AjaxResult selectMyBalance() {
        BigDecimal balance = userService.selectUserById(SecurityUtils.getUserId()).getBalance();
        return success(balance);
    } */

    /**
     * 账户充值
     */
/*     @PutMapping("/recharge/{amount}")
    public AjaxResult recharge(@PathVariable BigDecimal amount) {
        //查询充值前的余额
        BigDecimal oldBalance = userService.selectUserById(SecurityUtils.getUserId()).getBalance();

        //计算充值后的余额
        BigDecimal newBalance = oldBalance.add(amount);

        //更新账户余额
        SysUser user = new SysUser();
        user.setUserId(SecurityUtils.getUserId());
        user.setBalance(newBalance);

        return toAjax(userService.updateUser(user));
    } */
}
