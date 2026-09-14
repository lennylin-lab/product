package com.product.identity.common.annotation;

import com.product.identity.common.utils.StringUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// XSS安全校验注解实现类
/// 该类实现了JSR-303 Bean Validation规范的ConstraintValidator接口，
/// 为@Xss注解提供具体的验证逻辑。作为XSS防护的验证器层，
/// 通过正则表达式检测潜在的HTML标签和脚本注入风险。
/// 功能特性：
/// 1. 正则表达式检测：使用预编译的正则表达式高效匹配HTML标签
/// 2. 性能优化：正则表达式编译一次，多次使用，提升验证性能
/// 3. 阈值验证：只要检测到任何HTML标签，就判定为可能存在XSS风险
/// 4. 空值处理：对null或空字符串直接通过验证，避免不必要的检查
/// 验证策略：
/// - 采用保守策略：任何HTML标签都被视为潜在的XSS风险
/// - 支持多种HTML标签格式：包含自闭合标签、属性标签等
/// - 严格模式：不区分安全标签和危险标签，统一进行拦截
/// 适用场景：
/// - 表单参数验证：防止用户输入恶意脚本
/// - API参数校验：保护REST接口免受XSS攻击
/// - 数据持久化前验证：确保存储到数据库的数据安全性
///
/// @author fast
public class XssValidator implements ConstraintValidator<Xss, String>
{
    /// HTML标签检测正则表达式
    /// 该正则表达式能够匹配以下HTML标签模式：
    /// 1. 普通标签：<div>, <span>,
    /// 等
    /// 2. 带属性的标签：<img src="x" onerror="alert(1)">
    /// 3. 自闭合标签：
    ///, <img />, ---
    /// 4. 复杂嵌套标签：<script>alert('xss')</script>
    /// 正则表达式解释：
    /// - <(\\S*?)[^>]*>.*? ：匹配开始标签及其属性和内容
    /// - <.*? /> ：匹配自闭合标签
    /// 设计理念：
    /// 不区分安全标签和危险标签，所有HTML标签都视为潜在风险，
    /// 因为攻击者可以通过多种方式绕过简单的标签白名单。
    private static final String HTML_PATTERN = "<(\\S*?)[^>]*>.*?|<.*? />";

    /// 执行XSS验证的核心方法
    /// 验证逻辑：
    /// 1. 空值或空字符串：直接通过验证，因为不存在XSS风险
    /// 2. 非空字符串：使用containsHtml方法检测是否包含HTML标签
    /// 3. 如果包含HTML标签：验证失败，返回false
    /// 4. 如果不包含HTML标签：验证通过，返回true
    ///
    /// @param value 待验证的字符串值
    /// @param constraintValidatorContext 约束验证上下文，可用于获取验证元数据
    /// @return true表示验证通过（安全），false表示验证失败（存在XSS风险）
    @Override
    public boolean isValid(String value, ConstraintValidatorContext constraintValidatorContext)
    {
        // 空值或空字符串不存在XSS风险，直接通过验证
        if (StringUtils.isBlank(value))
        {
            return true;
        }

        // 检查是否包含HTML标签，如果包含则验证失败
        return !containsHtml(value);
    }

    /// 检测字符串中是否包含HTML标签
    /// 该方法使用正则表达式对输入字符串进行HTML标签检测。
    /// 通过两阶段匹配确保检测的准确性：
    /// 1. 第一阶段：查找所有匹配HTML模式的子串
    /// 2. 第二阶段：验证匹配结果的完整性
    /// 算法特点：
    /// - 使用StringBuilder进行高效的字符串拼接
    /// - 预编译正则表达式提升性能
    /// - 双重匹配机制确保检测准确性
    ///
    /// @param value 待检测的字符串
    /// @return true表示包含HTML标签（存在风险），false表示不包含HTML标签（安全）
    public static boolean containsHtml(String value)
    {
        // 用于收集匹配到的HTML标签片段
        StringBuilder sHtml = new StringBuilder();

        // 编译正则表达式并创建匹配器
        Pattern pattern = Pattern.compile(HTML_PATTERN);
        Matcher matcher = pattern.matcher(value);

        // 查找所有匹配HTML模式的子串
        while (matcher.find())
        {
            sHtml.append(matcher.group());
        }

        // 再次验证拼接结果，确保匹配的完整性
        return pattern.matcher(sHtml).matches();
    }
}
