package com.product.identity.common.annotation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// XSS安全校验注解
/// 该注解用于在方法参数、字段等位置进行XSS攻击防护，基于JSR-303/JSR-349 Bean Validation规范。
/// 作为XSS防护的第二道防线，与XssFilter形成完整的防护体系。
/// 功能特性：
/// 1. 参数级别验证：可以精确控制到具体的请求参数或字段
/// 2. Bean Validation集成：无缝集成到Spring Validation框架中
/// 3. 自定义验证逻辑：通过XssValidator实现具体的HTML内容检测
/// 4. 灵活的消息配置：支持自定义验证失败消息
/// 5. 分组验证支持：支持Bean Validation的分组功能
/// 使用场景：
/// - Controller方法参数：直接对请求参数进行XSS验证
/// - DTO字段验证：对数据传输对象的字段进行安全检查
/// - 实体类字段：对JPA实体类字段进行持久化前验证
/// - Service方法参数：对业务方法的输入参数进行验证
/// 使用示例：
/// <pre>
/// // Controller参数验证
/// &#64;PostMapping("/save")
/// public AjaxResult save(&#64;Xss String content) {
///     // content已经过XSS验证
///     return service.save(content);
/// }
///
/// // DTO字段验证
/// public class ContentDTO {
///     &#64;Xss(message = "内容包含不安全的HTML标签")
///     private String content;
/// }
/// </pre>
/// 验证原理：
/// - 使用正则表达式检测HTML标签：&lt;(\\S*?)[^&gt;]*&gt;.*?|&lt;.*? /&gt;
/// - 对检测到的HTML内容进行XSS风险评估
/// - 支持与XssFilter配合使用，提供双重防护
///
/// @author fast
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.PARAMETER })
@Constraint(validatedBy = { XssValidator.class })
public @interface Xss
{
    /// 验证失败时的错误消息
    /// 默认消息："不允许任何脚本运行"
    /// 可以在使用时自定义消息内容，提供更友好的用户体验
    ///
    /// @return 验证失败时显示的错误消息
    String message() default "不允许任何脚本运行";

    /// 验证分组
    /// 用于Bean Validation的分组验证功能，可以根据不同的业务场景
    /// 使用不同的验证规则。默认为空数组，表示属于默认分组。
    /// 使用示例：
    /// <pre>
    /// public interface CreateGroup {}
    /// public interface UpdateGroup {}
    ///
    /// &#64;Xss(groups = CreateGroup.class)
    /// private String content;
    /// </pre>
    ///
    /// @return 验证分组数组
    Class<?>[] groups() default {};

    /// Payload元数据
    /// 用于携带额外的验证元数据信息，通常用于自定义验证逻辑。
    /// 可以用于标记验证的严重程度、业务类型等信息。
    ///
    /// @return Payload类型数组
    Class<? extends Payload>[] payload() default {};
}
