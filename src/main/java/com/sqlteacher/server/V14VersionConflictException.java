package com.sqlteacher.server;

final class V14VersionConflictException extends RuntimeException {
    V14VersionConflictException(String message) {
        super(message);
    }
}
