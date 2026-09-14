package com.product.identity.common.utils.ip;

import com.product.identity.common.config.ProductConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * 获取地址类
 *
 * @author fast
 */
@Slf4j
public class AddressUtils
{
    // IP地址查询
    public static final String IP_URL = "http://whois.pconline.com.cn/ipJson.jsp";

    // 未知地址
    public static final String UNKNOWN = "XX XX";

    public static String getRealAddressByIP(String ip)
    {
        // 内网不查询
        if (IpUtils.internalIp(ip))
        {
            return "内网IP";
        }
        if (ProductConfig.isAddressEnabled())
        {
            // 单体此处调用外部 ip 归属地查询（pconline）；identity 首版不引外呼依赖，
            // 仓库配置从未开启 product.addressEnabled（默认 false，行为为 UNKNOWN），语义保持。
            log.warn("identity 未启用外网 IP 归属地解析: {}", ip);
        }
        return UNKNOWN;
    }
}
