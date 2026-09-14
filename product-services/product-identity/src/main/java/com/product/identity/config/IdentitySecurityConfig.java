package com.product.identity.config;

import com.product.identity.auth.service.impl.UserDetailsServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 登录认证装配（identity 专属；单体 SecurityConfig 的 authenticationManager/passwordEncoder 移植）。
 *
 * <p>SecurityFilterChain 由 product-cloud-security 的本地验签安全链提供（ADR-0003），
 * 此处仅补齐 Identity 作为签发方所需的登录认证组件：DaoAuthenticationProvider
 * （UserDetailsServiceImpl + BCrypt，与单体一致）。</p>
 */
@Configuration
public class IdentitySecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsServiceImpl userDetailsService,
                                                        PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
