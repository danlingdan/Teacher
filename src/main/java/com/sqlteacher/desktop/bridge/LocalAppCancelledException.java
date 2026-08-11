package com.sqlteacher.desktop.bridge;

final class LocalAppCancelledException extends RuntimeException {
    LocalAppCancelledException() {
        super("The local application request was cancelled");
    }
}
