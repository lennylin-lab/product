package com.product.identity.common.core.redis;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 工具类
 *
 * <p>当前实现基于 `RedisTemplate`，不再依赖本地内存缓存。
 * 业务方只需要继续注入 `RedisCache`，无需改调用方式。</p>
 *
 * @author fast
 **/
@SuppressWarnings(value = { "unchecked", "rawtypes" })
@Component
public class RedisCache
{
    private final RedisTemplate<String, Object> redisTemplate;

    public RedisCache(RedisTemplate<String, Object> redisTemplate)
    {
        this.redisTemplate = redisTemplate;
    }

    private ValueOperations<String, Object> valueOps()
    {
        return redisTemplate.opsForValue();
    }

    private HashOperations<String, Object, Object> hashOps()
    {
        return redisTemplate.opsForHash();
    }

    private SetOperations<String, Object> setOps()
    {
        return redisTemplate.opsForSet();
    }

    private ListOperations<String, Object> listOps()
    {
        return redisTemplate.opsForList();
    }

    /**
     * 缓存基本的对象，Integer、String、实体类等
     *
     * @param key 缓存的键值
     * @param value 缓存的值
     */
    public <T> void setCacheObject(final String key, final T value)
    {
        valueOps().set(key, value);
    }

    /**
     * 缓存基本的对象，Integer、String、实体类等
     *
     * @param key 缓存的键值
     * @param value 缓存的值
     * @param timeout 时间
     * @param timeUnit 时间颗粒度
     */
    public <T> void setCacheObject(final String key, final T value, final Integer timeout, final TimeUnit timeUnit)
    {
        valueOps().set(key, value, timeout, timeUnit);
    }

    /**
     * 判断 key是否存在
     *
     * @param key 键
     * @return true 存在 false不存在
     */
    public Boolean hasKey(String key)
    {
        return redisTemplate.hasKey(key);
    }

    /**
     * 获得缓存的基本对象。
     *
     * @param key 缓存键值
     * @return 缓存键值对应的数据
     */
    public <T> T getCacheObject(final String key)
    {
        return (T) valueOps().get(key);
    }

    /**
     * 删除单个对象
     *
     * @param key
     */
    public boolean deleteObject(final String key)
    {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    /**
     * 删除集合对象
     *
     * @param collection 多个对象
     * @return
     */
    public boolean deleteObject(final Collection collection)
    {
        if (collection == null || collection.isEmpty())
        {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.delete(collection));
    }

    /**
     * 获得缓存的基本对象列表
     *
     * @param pattern 字符串前缀
     * @return 对象列表
     */
    public Collection<String> keys(final String pattern)
    {
        Set<String> keySet = redisTemplate.keys(pattern);
        return keySet == null ? List.of() : keySet;
    }

    /**
     * 设置有效时间
     *
     * @param key 键
     * @param timeout 超时时间
     * @return true=设置成功；false=设置失败
     */
    public boolean expire(final String key, final long timeout)
    {
        return expire(key, timeout, TimeUnit.SECONDS);
    }

    /**
     * 设置有效时间
     *
     * @param key 键
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return true=设置成功；false=设置失败
     */
    public boolean expire(final String key, final long timeout, final TimeUnit unit)
    {
        Boolean success = redisTemplate.expire(key, timeout, unit);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 获取有效时间
     *
     * @param key 键
     * @return 有效时间，单位秒；-2 表示 key 不存在
     */
    public long getExpire(final String key)
    {
        Long expire = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return expire == null ? -2 : expire;
    }

    /**
     * 缓存 List 数据
     *
     * @param key 缓存键值
     * @param dataList 待缓存的 List 数据
     * @return 缓存的对象数量
     */
    public <T> long setCacheList(final String key, final List<T> dataList)
    {
        if (dataList == null)
        {
            return 0L;
        }
        redisTemplate.delete(key);
        if (!dataList.isEmpty())
        {
            listOps().rightPushAll(key, dataList.toArray());
        }
        return dataList.size();
    }

    /**
     * 获得缓存的 list 对象
     *
     * @param key 缓存的键值
     * @return 缓存键值对应的数据
     */
    public <T> List<T> getCacheList(final String key)
    {
        return (List<T>) listOps().range(key, 0, -1);
    }

    /**
     * 缓存 Set
     *
     * @param key 缓存键值
     * @param dataSet 缓存的数据
     * @return 缓存数据的对象数量
     */
    public <T> long setCacheSet(final String key, final Set<T> dataSet)
    {
        if (dataSet == null)
        {
            return 0L;
        }
        redisTemplate.delete(key);
        if (!dataSet.isEmpty())
        {
            setOps().add(key, dataSet.toArray());
        }
        return dataSet.size();
    }

    /**
     * 获得缓存的 set
     *
     * @param key 键
     * @return 值
     */
    public <T> Set<T> getCacheSet(final String key)
    {
        return (Set<T>) setOps().members(key);
    }

    /**
     * 缓存 Map
     *
     * @param key 键
     * @param dataMap 值
     */
    public <T> void setCacheMap(final String key, final Map<String, T> dataMap)
    {
        if (dataMap != null && !dataMap.isEmpty())
        {
            hashOps().putAll(key, dataMap);
        }
    }

    /**
     * 获得缓存的 Map
     *
     * @param key 键
     * @return 值
     */
    public <T> Map<String, T> getCacheMap(final String key)
    {
        return (Map<String, T>) (Map) hashOps().entries(key);
    }

    /**
     * 往 Hash 中存入数据
     *
     * @param key Redis 键
     * @param hKey Hash 键
     * @param value 值
     */
    public <T> void setCacheMapValue(final String key, final String hKey, final T value)
    {
        hashOps().put(key, hKey, value);
    }

    /**
     * 获取 Hash 中的数据
     *
     * @param key 键
     * @param hKey Hash 键
     * @return Hash 中的对象
     */
    public <T> T getCacheMapValue(final String key, final String hKey)
    {
        return (T) hashOps().get(key, hKey);
    }

    /**
     * 获取多个 Hash 中的数据
     *
     * @param key 键
     * @param hKeys Hash 键集合
     * @return Hash 对象集合
     */
    public <T> List<T> getMultiCacheMapValue(final String key, final Collection<Object> hKeys)
    {
        return (List<T>) hashOps().multiGet(key, hKeys);
    }

    /**
     * 删除 Hash 中的某条数据
     *
     * @param key 键
     * @param hKey Hash 键
     * @return 是否成功
     */
    public boolean deleteCacheMapValue(final String key, final String hKey)
    {
        Long deleted = hashOps().delete(key, hKey);
        return deleted != null && deleted > 0;
    }
}
