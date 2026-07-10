package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.application.config.AppConfigurationService;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.error.ApplicationExceptionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class SqlTeacherApplicationConfigTest {
    @Test
    void shouldCreateStageOneApplicationBeans() {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(SqlTeacherApplicationConfig.class)) {
            assertNotNull(context.getBean(AppConfigurationService.class));
            assertNotNull(context.getBean(DatabaseInitializationService.class));
            assertNotNull(context.getBean(AiStatusService.class));
            assertNotNull(context.getBean(ApplicationExceptionMapper.class));
        }
    }
}
