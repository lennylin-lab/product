package com.product.identity.auth.controller;

import com.product.identity.common.config.ProductConfig;
import com.product.identity.common.core.result.AjaxResult;
import com.product.identity.common.utils.StringUtils;
import com.product.identity.common.utils.file.FileUploadUtils;
import com.product.identity.common.utils.file.MimeTypeUtils;
import com.product.identity.core.controller.BaseController;
import com.product.cloud.security.auth.UserPrincipalView;
import com.product.identity.auth.service.JwtTokenService;
import com.product.identity.core.utils.SecurityUtils;
import com.product.identity.domain.entity.SysUser;
import com.product.identity.system.service.ISysUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 个人信息 业务处理（单体 SysProfileController 的 identity 移植，行为一致）。
 *
 * <p>差异说明：单体注入的 AliOssUtil 在该控制器中未参与任何端点逻辑，identity 移植未保留；
 * jwtUtils.setLoginUser 为单体空实现语义，由 {@link JwtTokenService#setLoginUser} 保持。</p>
 */
@Slf4j
@RestController
@RequestMapping("/system/user/profile")
public class SysProfileController extends BaseController {

    @Autowired
    private ISysUserService userService;

    @Autowired
    private JwtTokenService tokenService;

    /**
     * 个人信息
     */
    @GetMapping
    public AjaxResult profile() {
        UserPrincipalView loginUser = getLoginUser();
        // JWT 中只存了部分字段，这里重新查询完整信息
        SysUser dbUser = userService.selectUserById(loginUser.getUserId());
        log.info("获取个人信息: {}", dbUser);
        AjaxResult ajax = AjaxResult.success(dbUser);
        ajax.put("roleGroup", userService.selectUserRoleGroup(loginUser.getUsername()));
        return ajax;
    }

    /**
     * 修改用户
     */
    @PutMapping
    public AjaxResult updateProfile(@RequestBody SysUser user) {
        UserPrincipalView loginUser = getLoginUser();
        // 与单体一致：以当前登录态中的用户为基底合并邮箱/手机号后做唯一性校验
        SysUser currentUser = userService.selectUserById(loginUser.getUserId());
        currentUser.setEmail(user.getEmail());
        currentUser.setPhonenumber(user.getPhonenumber());
        if (StringUtils.isNotEmpty(user.getPhonenumber()) && !userService.checkPhoneUnique(currentUser)) {
            return error("修改用户'" + loginUser.getUsername() + "'失败，手机号码已存在");
        }
        if (StringUtils.isNotEmpty(user.getEmail()) && !userService.checkEmailUnique(currentUser)) {
            return error("修改用户'" + loginUser.getUsername() + "'失败，邮箱账号已存在");
        }
        if (userService.updateUserProfile(currentUser)) {
            // 更新缓存用户信息（单体为空实现语义）
            tokenService.setLoginUser(null);
            return success();
        }
        return error("修改个人信息异常，请联系管理员");
    }

    /**
     * 重置密码
     */
    @PutMapping("/updatePwd")
    public AjaxResult updatePwd(@RequestBody Map<String, String> params) {
        String oldPassword = params.get("oldPassword");
        String newPassword = params.get("newPassword");
        UserPrincipalView loginUser = getLoginUser();
        String userName = loginUser.getUsername();

        // 从数据库获取用户信息，确保密码字段正确
        SysUser user = userService.selectUserByUserName(userName);
        String password = user.getPassword();

        if (!SecurityUtils.matchesPassword(oldPassword, password)) {
            return error("修改密码失败，旧密码错误");
        }
        if (SecurityUtils.matchesPassword(newPassword, password)) {
            return error("新密码不能与旧密码相同");
        }
        newPassword = SecurityUtils.encryptPassword(newPassword);
        if (userService.resetUserPwd(userName, newPassword)) {
            // 更新缓存用户密码（单体为空实现语义）
            tokenService.setLoginUser(null);
            return success();
        }
        return error("修改密码异常，请联系管理员");
    }

    /**
     * 头像上传
     */
    @PostMapping("/avatar")
    public AjaxResult avatar(@RequestParam("avatarfile") MultipartFile file) throws Exception {
        if (!file.isEmpty()) {
            UserPrincipalView loginUser = getLoginUser();
            String avatar = FileUploadUtils.upload(ProductConfig.getAvatarPath(), file, MimeTypeUtils.IMAGE_EXTENSION);
            if (userService.updateUserAvatar(loginUser.getUsername(), avatar)) {
                AjaxResult ajax = AjaxResult.success();
                ajax.put("imgUrl", avatar);
                // 更新缓存用户头像（单体为空实现语义）
                tokenService.setLoginUser(null);
                return ajax;
            }
        }
        return error("上传图片异常，请联系管理员");
    }
}
