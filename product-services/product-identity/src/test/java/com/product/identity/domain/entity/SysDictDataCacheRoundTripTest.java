package com.product.identity.domain.entity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.product.identity.config.RedisConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #2 回归：字典缓存"写得出、读不回"的自我污染。
 *
 * <p>SysDictData 同时存在 @Data 生成的 isDefault 与手写 {@code getDefault()}，
 * 序列化会额外写出 {@code default} 属性；缓存反序列化若严格校验未知字段，
 * 则缓存写入一轮后该类型字典的所有读请求持续失败。</p>
 *
 * <p>断言三点：写入的缓存 JSON 确实含 {@code default}（根因固化）；严格未知字段
 * 校验的实体级读取复现原始故障；RedisConfig 缓存 mapper 容忍未知字段且 isDefault
 * 正确往返（修复生效）。</p>
 */
class SysDictDataCacheRoundTripTest {

    private static SysDictData dictData() {
        SysDictData data = new SysDictData();
        data.setDictCode(100L);
        data.setDictSort(1L);
        data.setDictLabel("显示");
        data.setDictValue("0");
        data.setDictType("sys_normal_disable");
        data.setIsDefault("Y");
        return data;
    }

    @Test
    @DisplayName("缓存写入的 JSON 含 default 属性（根因固化）")
    void serializedCacheJsonContainsDefaultProperty() throws Exception {
        String json = RedisConfig.cacheObjectMapper().writeValueAsString(cacheValue());
        assertTrue(json.contains("\"default\""), "序列化应写出 default 属性: " + json);
        assertTrue(json.contains("\"isDefault\""), "isDefault 字段应正常写出: " + json);
    }

    @Test
    @DisplayName("实体级严格读取因 default 字段失败（复现原始故障）")
    void strictEntityReadFailsOnDefaultProperty() throws Exception {
        ObjectMapper strict = RedisConfig.cacheObjectMapper();
        strict.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        String entityJson = strict.writeValueAsString(dictData());
        try {
            strict.readValue(entityJson, SysDictData.class);
            assertTrue(false, "严格读取应抛 Unrecognized field 异常");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("default"), "失败原因应是未知字段 default: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("缓存 mapper 往返：default 字段不再炸读，isDefault 正确还原")
    void cacheMapperRoundTripsDictDataList() throws Exception {
        ObjectMapper strict = RedisConfig.cacheObjectMapper();
        strict.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        String cached = strict.writeValueAsString(cacheValue());
        boolean poisonedReadFailed = false;
        try {
            strict.readValue(cached, Object.class);
        } catch (Exception e) {
            poisonedReadFailed = e.getMessage().contains("default");
        }
        assertTrue(poisonedReadFailed, "修复前缓存读回应因 default 字段失败");

        // 修复后（缓存 mapper 默认容忍未知字段）：与 RedisCache.getCacheObject 同为 Object 目标读取
        ObjectMapper mapper = RedisConfig.cacheObjectMapper();
        Object roundTripped = mapper.readValue(mapper.writeValueAsString(cacheValue()), Object.class);
        assertTrue(roundTripped instanceof List, "缓存值应还原为 List: " + roundTripped);
        assertEquals(1, ((List<?>) roundTripped).size());
        Object element = ((List<?>) roundTripped).get(0);
        assertTrue(element instanceof SysDictData, "元素应还原为 SysDictData: " + element);
        assertEquals("Y", ((SysDictData) element).getIsDefault());
        assertTrue(((SysDictData) element).getDefault());
    }

    /** 与 SysDictTypeServiceImpl 缓存写入一致：DB 返回的 List 为 ArrayList（非 final，根级带类型包装）。 */
    private static List<SysDictData> cacheValue() {
        return new ArrayList<>(List.of(dictData()));
    }
}
