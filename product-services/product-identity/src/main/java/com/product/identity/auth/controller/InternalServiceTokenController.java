package com.product.identity.auth.controller;

import com.product.identity.common.constant.Constants;
import com.product.identity.common.core.result.AjaxResult;
import com.product.identity.auth.service.SysLoginService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务身份令牌签发（Phase 4，ADR-0003 决策 7 的服务身份最小实现）。
 *
 * <p>背景：异步排程（product-planning 后台线程）调用 demand/master-data 内部契约时
 * 无请求上下文可透传用户 JWT（{@code FeignForwardAuthConfig} 拿不到 Authorization），
 * 需要以服务自身身份获得合法签名 token。{@code /login} 的人机验证码对服务不可用，
 * 故提供免验证码的服务凭据登录端点：凭据本身即认证（与 /login 同一
 * AuthenticationManager/BCrypt 校验、同一 RS256 JwtTokenService 签发）。</p>
 *
 * <p>暴露面控制：路径位于 {@code /internal/**} —— 网关 InternalPathDenyFilter 对外显式
 * 404（Phase 4 必修项），仅内网直连端口可达；服务安全链对本端点 permitAll
 * （{@code product.security.permit-urls}），认证由请求体中的服务凭据承担。
 * 服务账号（planning_svc，见 identity_schema.sql 种子）仅具备普通业务域访问语义
 * （业务域端点仅要求登录，token permissions 为空集）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/internal/identity")
public class InternalServiceTokenController {

    @Autowired
    private SysLoginService loginService;

    /** 服务凭据登录（免验证码；凭据错误与单体登录异常同源）。 */
    @PostMapping("/service-token")
    public AjaxResult serviceToken(@RequestBody ServiceCredential credential) {
        AjaxResult ajax = AjaxResult.success();
        String token = loginService.loginWithoutCaptcha(credential.getUsername(), credential.getPassword());
        ajax.put(Constants.TOKEN, token);
        return ajax;
    }

    /** 服务凭据（shape 与 LoginDTO 的用户名/密码一致，无验证码字段）。 */
    @Data
    public static class ServiceCredential {
        private String username;
        private String password;
    }
}
