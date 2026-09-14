package com.product.execution.config;

import com.product.execution.common.core.result.AjaxResult;
import com.product.execution.common.utils.StringUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;

/**
 * SQL/数据访问异常映射（单体 {@code product-framework} GlobalExceptionHandler 对应
 * {@code @ExceptionHandler} 的 identity 逐字节移植，Phase 2 检查项修正：identity 已有真实
 * MyBatis 访问，SQL 异常的错误体必须与单体一致）。
 *
 * <p>错误文案（单体 getSqlErrorMessage 原文）：Duplicate entry → "数据已存在，请检查重复数据"、
 * foreign key constraint → "数据关联约束异常，请检查相关数据"、cannot be null → "必填字段不能为空"、
 * Data too long → "输入数据过长，请检查字段长度"、Incorrect string value → "数据格式错误，请检查编码格式"、
 * 其余 → "数据库操作异常：<原始消息>"、无消息 → "数据库操作异常，请联系管理员"。</p>
 *
 * <p>独立成类并标注最高 @Order：DataAccessException 同时命中 cloud-common 通用 advice 的
 * RuntimeException 兜底，必须让本 advice（最具体映射）先被解析；未命中 SQL 的异常照常
 * 落到通用 advice，行为不变。放在 identity 而非 cloud-common：mybatis/spring-tx 类型仅
 * 数据库服务持有，cloud-common 的通用 advice 被无 DB 骨架共享，类路径缺少这些类型时
 * @ExceptionHandler 类字面量会在 Bean 初始化内省时抛 NoClassDefFoundError。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalSqlExceptionHandler {

    @ExceptionHandler({
            SQLException.class,
            DataAccessException.class,
            org.apache.ibatis.exceptions.PersistenceException.class,
            org.mybatis.spring.MyBatisSystemException.class
    })
    public AjaxResult handleSqlException(Exception e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',发生SQL异常", requestURI, e);

        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        String message = getSqlErrorMessage(cause);
        return AjaxResult.error(message);
    }

    private String getSqlErrorMessage(Throwable e) {
        if (e == null) {
            return "数据库操作异常，请联系管理员";
        }

        String errorMsg = e.getMessage();
        if (StringUtils.isEmpty(errorMsg)) {
            return "数据库操作异常，请联系管理员";
        }

        if (errorMsg.contains("Duplicate entry")) {
            return "数据已存在，请检查重复数据";
        } else if (errorMsg.contains("foreign key constraint")) {
            return "数据关联约束异常，请检查相关数据";
        } else if (errorMsg.contains("cannot be null")) {
            return "必填字段不能为空";
        } else if (errorMsg.contains("Data too long")) {
            return "输入数据过长，请检查字段长度";
        } else if (errorMsg.contains("Incorrect string value")) {
            return "数据格式错误，请检查编码格式";
        } else {
            return "数据库操作异常：" + errorMsg;
        }
    }
}
