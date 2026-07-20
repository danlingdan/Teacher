package com.sqlteacher.application.error;

import com.sqlteacher.domain.SqlTeacherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

public final class DefaultApplicationExceptionMapper implements ApplicationExceptionMapper {
    private static final Logger log = LoggerFactory.getLogger(DefaultApplicationExceptionMapper.class);
    private static final Map<String, ErrorPresentation> PRESENTATIONS = Map.ofEntries(
        Map.entry("CONFIG_NOT_FOUND", presentation("找不到应用配置，请重新安装或联系教师。", false)),
        Map.entry("CONFIG_LOAD_FAILED", presentation("应用配置读取失败，请重启后重试。", true)),
        Map.entry("CONFIG_INVALID", presentation("应用配置无效，请联系教师检查配置。", false)),
        Map.entry("SQLITE_INIT_FAILED", presentation("演示数据库初始化失败，请检查数据目录是否可写。", true)),
        Map.entry("CONNECTION_PROFILE_FAILED", presentation("数据库连接配置读取或保存失败，请重试。", true)),
        Map.entry("DATABASE_METADATA_FAILED", presentation("表结构读取失败，请检查数据库连接后重试。", true)),
        Map.entry("SQL_BLOCKED", presentation("SQL 被安全规则拦截，请检查是否包含危险操作或多条语句。", false)),
        Map.entry("MOCK_SQL_BLOCKED", presentation("SQL 被安全规则拦截，请检查是否包含危险操作或多条语句。", false)),
        Map.entry("SQL_CONFIRMATION_REQUIRED", presentation("该 SQL 可能修改数据，确认风险后才能执行。", false)),
        Map.entry("SQL_EXECUTION_FAILED", presentation("SQL 执行失败，请检查语法、表名和字段名后重试。", true))
    );

    @Override
    public ApplicationError map(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable must not be null");

        if (throwable instanceof SqlTeacherException exception) {
            String code = hasText(exception.errorCode()) ? exception.errorCode() : "APPLICATION_ERROR";
            ErrorPresentation presentation = PRESENTATIONS.get(code);
            String userMessage = presentation != null
                ? presentation.userMessage()
                : "操作未能完成，请重试或联系教师。";
            boolean retryable = presentation != null && presentation.retryable();
            log.warn("Application operation failed, code={}", code, exception);
            return new ApplicationError(
                code,
                ApplicationErrorType.APPLICATION,
                userMessage,
                retryable
            );
        }
        if (throwable instanceof IllegalArgumentException) {
            log.warn("Application request validation failed", throwable);
            return new ApplicationError(
                "INVALID_REQUEST",
                ApplicationErrorType.VALIDATION,
                "输入内容无效，请检查后重试。",
                false
            );
        }

        log.error("Unexpected application failure", throwable);
        return new ApplicationError(
            "UNEXPECTED_ERROR",
            ApplicationErrorType.UNEXPECTED,
            "操作失败，请重试；如果仍然失败，请联系教师。",
            true
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ErrorPresentation presentation(String userMessage, boolean retryable) {
        return new ErrorPresentation(userMessage, retryable);
    }

    private record ErrorPresentation(String userMessage, boolean retryable) {
    }
}
