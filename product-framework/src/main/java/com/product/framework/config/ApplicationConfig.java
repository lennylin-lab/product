package com.product.framework.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.product.common.utils.spring.SpringUtils;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.TimeZone;

/// Spring Boot应用程序核心配置类
/// 该配置类负责应用程序的基础设施配置，包括：
/// 1. AOP代理对象暴露配置（支持SpringUtils.getAopProxy()方法）
/// 2. Jackson JSON序列化时区配置
/// 这是应用程序启动时加载的核心配置之一，确保各组件能够正常工作。
///
/// @author fast
/// @version 1.0
/// @since 1.0
@Configuration
/// 启用Spring AOP代理功能
/// 通过exposeProxy=true配置，允许通过AopContext.currentProxy()获取当前对象的代理。
/// 这个配置对于在同一个类中调用带有事务注解或切面注解的方法时非常重要，
/// 可以确保AOP功能正常工作（如事务、缓存、日志等切面）。
///
/// @see SpringUtils#getAopProxy(Object)
@EnableAspectJAutoProxy(exposeProxy = true)
public class ApplicationConfig
{
    /// Jackson JSON序列化时区配置Bean
    /// 配置Jackson在序列化和反序列化日期时间类型时使用的时区。
    /// 使用系统默认时区确保时间处理的准确性，避免因时区问题导致的时间错乱。
    /// 该配置会影响以下场景：
    /// 1. REST API响应中的日期时间字段格式化
    /// 2. 接收请求中的日期时间参数解析
    /// 3. 数据库时间字段的JSON序列化
    ///
    /// @return Jackson2ObjectMapperBuilderCustomizer 时区配置定制器
    ///
    /// @example 配置生效后的效果
    ///          如果系统时区为Asia/Shanghai，那么所有JSON中的时间都会按照北京时间处理
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonObjectMapperCustomization()
    {
        // 允许 LocalDateTime 反序列化同时接受“yyyy-MM-dd HH:mm:ss”和仅日期“yyyy-MM-dd”，
        // 当仅有日期时默认补 00:00:00，避免前端只传日期导致解析失败。
        DateTimeFormatter flexibleLdtFormatter = new DateTimeFormatterBuilder()
                .appendPattern("yyyy-MM-dd")
                .optionalStart().appendPattern(" HH:mm:ss").optionalEnd()
                .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
                .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
                .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
                .toFormatter();

        return jacksonObjectMapperBuilder -> jacksonObjectMapperBuilder
                .timeZone(TimeZone.getDefault())
                // 雪花ID为19位Long，超出JS Number.MAX_SAFE_INTEGER，统一序列化为String避免前端精度丢失
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance)
                .serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(flexibleLdtFormatter));
    }
}
