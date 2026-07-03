package com.sqlteacher.domain;

public class SqlTeacherException extends RuntimeException {
    private final String errorCode;

    public SqlTeacherException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SqlTeacherException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
