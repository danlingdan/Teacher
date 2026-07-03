package com.sqlteacher.infrastructure.environment;

public record VerificationItem(String name, VerificationStatus status, String detail) {
    public static VerificationItem passed(String name, String detail) {
        return new VerificationItem(name, VerificationStatus.PASS, detail);
    }

    public static VerificationItem warning(String name, String detail) {
        return new VerificationItem(name, VerificationStatus.WARNING, detail);
    }

    public static VerificationItem failed(String name, String detail) {
        return new VerificationItem(name, VerificationStatus.FAIL, detail);
    }
}
