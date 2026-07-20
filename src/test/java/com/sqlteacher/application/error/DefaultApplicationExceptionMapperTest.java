package com.sqlteacher.application.error;

import com.sqlteacher.domain.SqlTeacherException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultApplicationExceptionMapperTest {
    private final DefaultApplicationExceptionMapper mapper = new DefaultApplicationExceptionMapper();

    @Test
    void shouldPreserveKnownApplicationErrorCodeAndSafeMessage() {
        ApplicationError error = mapper.map(
            new SqlTeacherException("SQLITE_INIT_FAILED", "The demo database could not be prepared.")
        );

        assertEquals("SQLITE_INIT_FAILED", error.code());
        assertEquals(ApplicationErrorType.APPLICATION, error.type());
        assertEquals("演示数据库初始化失败，请检查数据目录是否可写。", error.userMessage());
        assertTrue(error.retryable());
    }

    @Test
    void shouldHideUnexpectedExceptionDetails() {
        ApplicationError error = mapper.map(new IllegalStateException("internal diagnostic detail"));

        assertEquals("UNEXPECTED_ERROR", error.code());
        assertEquals(ApplicationErrorType.UNEXPECTED, error.type());
        assertFalse(error.userMessage().contains("diagnostic"));
        assertTrue(error.retryable());
    }

    @Test
    void shouldMapInvalidInputAsValidationError() {
        ApplicationError error = mapper.map(new IllegalArgumentException("sql must not be blank"));

        assertEquals("INVALID_REQUEST", error.code());
        assertEquals(ApplicationErrorType.VALIDATION, error.type());
        assertFalse(error.retryable());
    }

    @Test
    void shouldProvideSafeFallbackForIncompleteApplicationException() {
        ApplicationError error = mapper.map(new SqlTeacherException(null, null));

        assertEquals("APPLICATION_ERROR", error.code());
        assertEquals("操作未能完成，请重试或联系教师。", error.userMessage());
    }

    @Test
    void shouldHideDatabaseDetailsBehindKnownSafeMessage() {
        ApplicationError error = mapper.map(
            new SqlTeacherException("SQL_EXECUTION_FAILED", "near secret_table: syntax error")
        );

        assertEquals("SQL 执行失败，请检查语法、表名和字段名后重试。", error.userMessage());
        assertFalse(error.userMessage().contains("secret_table"));
        assertTrue(error.retryable());
    }

    @Test
    void shouldHideConnectionProfileStorageDetails() {
        ApplicationError error = mapper.map(
            new SqlTeacherException("CONNECTION_PROFILE_FAILED", "no such table: connection_profiles")
        );

        assertEquals("数据库连接配置读取或保存失败，请重试。", error.userMessage());
        assertFalse(error.userMessage().contains("connection_profiles"));
        assertTrue(error.retryable());
    }

    @Test
    void shouldMapDatabaseAuthenticationFailureWithoutLeakingDriverDetails() {
        ApplicationError error = mapper.map(
            new SqlTeacherException("DATABASE_AUTHENTICATION_FAILED", "Access denied; password=secret")
        );

        assertEquals("数据库身份验证失败，请检查用户名和临时密码。", error.userMessage());
        assertFalse(error.userMessage().contains("secret"));
        assertFalse(error.retryable());
    }
}
