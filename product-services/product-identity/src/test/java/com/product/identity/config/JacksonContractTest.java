package com.product.identity.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.product.identity.common.core.result.AjaxResult;
import com.product.identity.domain.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 响应序列化契约测试：验证 ApplicationConfig 的 Jackson 定制（Long→String、
 * LocalDateTime→"yyyy-MM-dd HH:mm:ss"）与单体一致；AjaxResult 信封键序与单体相同。
 */
class JacksonContractTest {

    private ObjectMapper mapperWithCustomizer() {
        org.springframework.http.converter.json.Jackson2ObjectMapperBuilder builder =
                org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json();
        new ApplicationConfig().jacksonObjectMapperCustomization().customize(builder);
        return builder.build();
    }

    @Test
    void longValuesShouldSerializeAsStringsLikeMonolith() throws Exception {
        SysUser user = new SysUser();
        user.setUserId(1L);
        String json = mapperWithCustomizer().writeValueAsString(user);
        assertTrue(json.contains("\"userId\":\"1\""), json);
    }

    @Test
    void localDateTimeShouldSerializeAsMonolithPattern() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("loginDate", LocalDateTime.of(2026, 9, 14, 10, 2, 3));
        String json = mapperWithCustomizer().writeValueAsString(payload);
        assertTrue(json.contains("2026-09-14 10:02:03"), json);
    }

    @Test
    void ajaxResultEnvelopeShouldCarryCodeMsgAndOptionalData() throws Exception {
        AjaxResult ok = AjaxResult.success();
        ok.put("token", "jwt");
        String json = mapperWithCustomizer().writeValueAsString(ok);
        // 信封键集合：code/msg/token（无 data——data 为 null 时按单体语义不输出）
        assertTrue(json.contains("\"code\":200"), json);
        assertTrue(json.contains("\"msg\":\"操作成功\""), json);
        assertTrue(json.contains("\"token\":\"jwt\""), json);
        assertEquals("1", mapperWithCustomizer().writeValueAsString(Map.of("v", 1L)).replaceAll("[^0-9]", ""));
    }

    @Test
    void permissionsSetShouldSerializeAsArray() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("permissions", Set.of("*:*:*"));
        String json = mapperWithCustomizer().writeValueAsString(payload);
        assertEquals("{\"permissions\":[\"*:*:*\"]}", json);
    }

    @Test
    void passwordShouldNotAppearInSerializedResponse() throws Exception {
        // issue #9：/getInfo、/system/user/{id}、/system/user/profile 响应体不得泄露 BCrypt 哈希
        SysUser user = new SysUser();
        user.setUserId(1L);
        user.setPassword("$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2");
        String json = mapperWithCustomizer().writeValueAsString(user);
        assertTrue(!json.contains("password"), json);
        assertTrue(!json.contains("$2a$"), json);
    }

    @Test
    void passwordShouldStillDeserializeFromRequestBody() throws Exception {
        // issue #9 回归：WRITE_ONLY 只关序列化，请求体读入不受影响（未来恢复写端点时语义不变）
        SysUser user = mapperWithCustomizer().readValue(
                "{\"userId\":\"1\",\"password\":\"$2a$10$abc\"}", SysUser.class);
        assertEquals("$2a$10$abc", user.getPassword());
    }
}
