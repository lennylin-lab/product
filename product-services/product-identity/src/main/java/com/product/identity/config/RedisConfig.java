package com.product.identity.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * RedisTemplate 配置（单体 product-cache RedisConfig 的 identity 移植，序列化语义一致）：
 * key 用 String、value 用带类型信息的 Jackson JSON（captcha 码、重试计数、字典 List 均可往返）。
 * 与单体差异：不含 CACHE_INVALIDATE_TOPIC 发布订阅与 Caffeine 本地缓存
 * （identity 无跨进程二级缓存失效需求），缓存命名空间隔离由 CacheConstants 的 identity: 前缀保证。
 *
 * <p>issue #2 决议：缓存 mapper 关闭 FAIL_ON_UNKNOWN_PROPERTIES。实体侧"手写 getter 与
 * @Data 生成的 getter 并存"会序列化出多余属性（如 SysDictData.default），反序列化若严格校验
 * 则缓存写入一轮后所有读请求持续失败（缓存自我污染）。缓存读侧容忍未知字段即可修复，
 * 且不影响 HTTP 响应的序列化契约（响应 mapper 为独立实例）。</p>
 */
@Configuration
@EnableCaching
public class RedisConfig {

    /** 缓存专用 mapper：单一来源供 RedisTemplate 与往返测试共用，保证测试与运行时同配置。 */
    public static ObjectMapper cacheObjectMapper() {
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);
        om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return om;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        Jackson2JsonRedisSerializer<Object> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(cacheObjectMapper(), Object.class);

        template.setKeySerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
