package com.product.identity.common.constant;

/**
 * 缓存的key 常量
 *
 * @author fast
 */
public class CacheConstants
{
    /**
     * identity 缓存命名空间前缀（ADR-0005 §4：每服务独立 key prefix，缓存不作为跨服务事实源）。
     * 单体同名常量值为不带前缀的 "login_tokens:" 等；identity 服务按 ADR 统一加 "identity:" 前缀。
     */
    public static final String NAMESPACE = "identity:";

    /**
     * 登录用户 redis key
     */
    public static final String LOGIN_TOKEN_KEY = NAMESPACE + "login_tokens:";

    /**
     * 验证码 redis key
     */
    public static final String CAPTCHA_CODE_KEY = NAMESPACE + "captcha_codes:";

    /**
     * 参数管理 cache key
     */
    public static final String SYS_CONFIG_KEY = NAMESPACE + "sys_config:";

    /**
     * 字典管理 cache key
     */
    public static final String SYS_DICT_KEY = NAMESPACE + "sys_dict:";

    /**
     * 防重提交 redis key
     */
    public static final String REPEAT_SUBMIT_KEY = NAMESPACE + "repeat_submit:";

    /**
     * 登录账户密码错误次数 redis key
     */
    public static final String PWD_ERR_CNT_KEY = NAMESPACE + "pwd_err_cnt:";
}
