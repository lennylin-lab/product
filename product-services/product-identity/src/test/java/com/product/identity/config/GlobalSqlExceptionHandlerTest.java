package com.product.identity.config;

import com.product.identity.common.core.result.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SQL 异常映射测试：错误文案与单体 getSqlErrorMessage 逐字一致（Phase 2 检查项修正）。
 */
class GlobalSqlExceptionHandlerTest {

    private final GlobalSqlExceptionHandler handler = new GlobalSqlExceptionHandler();

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/system/dict/type");
    }

    private DataAccessException wrap(Throwable cause) {
        // 模拟 MyBatisSystemException → PersistenceException → SQL 异常的三层包装
        return new DataIntegrityViolationException("StatementCallback", cause);
    }

    @Test
    void duplicateEntryShouldReturnMonolithMessage() {
        AjaxResult result = handler.handleSqlException(
                wrap(new java.sql.SQLException("Duplicate entry 'admin' for key 'idx_sys_user_name'")), request());
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("数据已存在，请检查重复数据", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void foreignKeyShouldReturnMonolithMessage() {
        AjaxResult result = handler.handleSqlException(
                wrap(new java.sql.SQLException("Cannot add or update a child row: a foreign key constraint fails")), request());
        assertEquals("数据关联约束异常，请检查相关数据", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void nullColumnShouldReturnMonolithMessage() {
        AjaxResult result = handler.handleSqlException(
                wrap(new java.sql.SQLException("Column 'user_name' cannot be null")), request());
        assertEquals("必填字段不能为空", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void dataTooLongShouldReturnMonolithMessage() {
        AjaxResult result = handler.handleSqlException(
                wrap(new java.sql.SQLException("Data too long for column 'email'")), request());
        assertEquals("输入数据过长，请检查字段长度", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void incorrectStringShouldReturnMonolithMessage() {
        AjaxResult result = handler.handleSqlException(
                wrap(new java.sql.SQLException("Incorrect string value: '\\xF0\\x9F' for column 'remark'")), request());
        assertEquals("数据格式错误，请检查编码格式", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void otherSqlErrorShouldEmbedOriginalMessage() {
        AjaxResult result = handler.handleSqlException(
                wrap(new java.sql.SQLException("Table 'identity_db.sys_xxx' doesn't exist")), request());
        assertEquals("数据库操作异常：Table 'identity_db.sys_xxx' doesn't exist", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void causelessMessagelessExceptionShouldReturnGenericMessage() {
        AjaxResult result = handler.handleSqlException(new DataAccessException("") {
        }, request());
        assertEquals("数据库操作异常，请联系管理员", result.get(AjaxResult.MSG_TAG));
    }
}
