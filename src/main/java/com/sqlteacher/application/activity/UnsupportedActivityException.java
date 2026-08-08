package com.sqlteacher.application.activity;

public final class UnsupportedActivityException extends RuntimeException {
    private final String errorCode;

    public UnsupportedActivityException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
