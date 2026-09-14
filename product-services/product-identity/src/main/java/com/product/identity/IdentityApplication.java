package com.product.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * product-identity 启动骨架（Phase 1，空应用，不含业务代码）。
 *
 * <p>ADR-0002：登录、验证码、JWT 签发、用户/角色/菜单/字典/权限查询；
 * 拥有 sys_* 表（Phase 2 迁移）。</p>
 */
@SpringBootApplication
public class IdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
