package com.sqlteacher.application.error;

import com.sqlteacher.domain.SqlTeacherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public final class DefaultApplicationExceptionMapper implements ApplicationExceptionMapper {
    private static final Logger log = LoggerFactory.getLogger(DefaultApplicationExceptionMapper.class);

    @Override
    public ApplicationError map(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable must not be null");

        if (throwable instanceof SqlTeacherException exception) {
            String code = hasText(exception.errorCode()) ? exception.errorCode() : "APPLICATION_ERROR";
            String userMessage = hasText(exception.getMessage())
                ? exception.getMessage()
                : "The operation could not be completed.";
            log.warn("Application operation failed, code={}", code, exception);
            return new ApplicationError(
                code,
                ApplicationErrorType.APPLICATION,
                userMessage,
                false
            );
        }
        if (throwable instanceof IllegalArgumentException) {
            log.warn("Application request validation failed", throwable);
            return new ApplicationError(
                "INVALID_REQUEST",
                ApplicationErrorType.VALIDATION,
                "The input is invalid. Check it and try again.",
                false
            );
        }

        log.error("Unexpected application failure", throwable);
        return new ApplicationError(
            "UNEXPECTED_ERROR",
            ApplicationErrorType.UNEXPECTED,
            "The operation failed. Try again; if it still fails, contact the teacher.",
            true
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
