package com.sqlteacher.application.collaboration;

public final class AdminOperationRejectedException extends RuntimeException {
    private final String code;

    public AdminOperationRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
