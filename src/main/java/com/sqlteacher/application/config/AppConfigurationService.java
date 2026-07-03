package com.sqlteacher.application.config;

import com.sqlteacher.infrastructure.config.SqlTeacherProperties;

public interface AppConfigurationService {
    SqlTeacherProperties current();
}
