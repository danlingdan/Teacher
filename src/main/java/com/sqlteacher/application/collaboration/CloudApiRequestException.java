package com.sqlteacher.application.collaboration;

public final class CloudApiRequestException extends RuntimeException {
    private final int statusCode;
    private final String code;

    public CloudApiRequestException(int statusCode, String code, String message) {
        super(message);
        this.statusCode = statusCode;
        this.code = code == null || code.isBlank() ? "CLOUD_REQUEST_FAILED" : code;
    }

    public int statusCode() { return statusCode; }

    public String code() { return code; }

    public boolean retryable() {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }
}
