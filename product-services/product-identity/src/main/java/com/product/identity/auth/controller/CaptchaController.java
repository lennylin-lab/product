package com.product.identity.auth.controller;

import com.google.code.kaptcha.Producer;
import com.product.identity.common.config.ProductConfig;
import com.product.identity.common.constant.CacheConstants;
import com.product.identity.common.constant.Constants;
import com.product.identity.common.core.redis.RedisCache;
import com.product.identity.common.core.result.AjaxResult;
import com.product.identity.common.utils.uuid.IdUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.FastByteArrayOutputStream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 验证码操作处理
 *
 * @author fast
 */
@Slf4j
@RestController
public class CaptchaController
{
    @Resource(name = "captchaProducer")
    private Producer captchaProducer;

    @Resource(name = "captchaProducerMath")
    private Producer captchaProducerMath;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private ProductConfig productConfig;

    /**
     * 生成验证码
     */
    @GetMapping("/captchaImage")
    public AjaxResult getCode(HttpServletResponse response) throws IOException
    {
        AjaxResult ajax = AjaxResult.success();
        boolean captchaEnabled = true;

        ajax.put("captchaEnabled", captchaEnabled);
        if (!captchaEnabled)
        {
            return ajax;
        }

        // 用于唯一标识验证码
        String uuid = IdUtils.simpleUUID();

        // 缓存键
        String verifyKey = CacheConstants.CAPTCHA_CODE_KEY + uuid;

        String capStr = null, code = null;
        BufferedImage image = null;

        // 生成验证码
        String captchaType = productConfig.getCaptchaType();

        if ("math".equals(captchaType))
        {
            // 运算式验证码
            String capText = captchaProducerMath.createText();  // 生成运算式，类似2+3=@5
            capStr = capText.substring(0, capText.lastIndexOf("@"));    // 截取获得运算式2+3
            code = capText.substring(capText.lastIndexOf("@") + 1); // 截取获得结果5
            image = captchaProducerMath.createImage(capStr);    // 生成图片
        }
        else if ("char".equals(captchaType))
        {
            // 字符串验证码
            capStr = code = captchaProducer.createText();   // 随机字符序列
            image = captchaProducer.createImage(capStr);    // 生成图片
        }

        redisCache.setCacheObject(verifyKey, code, Constants.CAPTCHA_EXPIRATION, TimeUnit.MINUTES);
        // 转换流信息写出
        FastByteArrayOutputStream os = new FastByteArrayOutputStream();
        try
        {
            ImageIO.write(image, "jpg", os);
        }
        catch (IOException e)
        {
            return AjaxResult.error(e.getMessage());
        }

        // 响应中放入唯一标识uuid
        ajax.put("uuid", uuid);
        //
        // 与单体 com.product.common.utils.sign.Base64.encode 等价（标准 base64 编码）
        ajax.put("img", java.util.Base64.getEncoder().encodeToString(os.toByteArray()));
        return ajax;
    }
}
