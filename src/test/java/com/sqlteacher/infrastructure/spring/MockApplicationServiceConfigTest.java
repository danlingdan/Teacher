package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.nl2sql.Nl2SqlService;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MockApplicationServiceConfigTest {
    @Test
    void shouldExposeUiCallableMockBeans() {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(MockApplicationServiceConfig.class)) {
            SqlExecutionService executionService = context.getBean(SqlExecutionService.class);

            assertEquals(2, executionService.execute(new SqlExecutionRequest(
                "demo", "SELECT * FROM student", 100, Duration.ofSeconds(2)
            )).rows().size());
            assertEquals(1, context.getBean(DatabaseMetadataService.class).listTables("demo").size());
            assertNotNull(context.getBean(SqlRiskAnalysisService.class));
            assertNotNull(context.getBean(Nl2SqlService.class));
            assertNotNull(context.getBean(Nl2SqlSafetyService.class));
            assertNotNull(context.getBean(ApplicationExceptionMapper.class));
        }
    }
}
