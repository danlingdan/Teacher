package com.sqlteacher.server;

final class V19VersionConflictException extends RuntimeException {
    V19VersionConflictException(String message) {
        super(message);
    }
}
