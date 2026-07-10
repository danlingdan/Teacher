package com.sqlteacher.application.error;

public interface ApplicationExceptionMapper {
    ApplicationError map(Throwable throwable);
}
