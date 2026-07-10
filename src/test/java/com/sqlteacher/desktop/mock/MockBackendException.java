package com.sqlteacher.desktop.mock;

/**
 * Thrown by mock services to simulate a backend / interface failure that cannot be represented
 * inline by the corresponding response DTO (for example an NL2SQL model error or a database
 * initialization failure).
 */
public class MockBackendException extends RuntimeException {

    public MockBackendException(String message) {
        super(message);
    }
}
