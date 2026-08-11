package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.error.DefaultApplicationExceptionMapper;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.mock.MockAiStatusService;
import com.sqlteacher.application.mock.MockDatabaseMetadataService;
import com.sqlteacher.application.mock.MockNl2SqlService;
import com.sqlteacher.application.mock.MockLearningEventService;
import com.sqlteacher.application.mock.MockSqlExecutionService;
import com.sqlteacher.application.mock.MockSqlRiskAnalysisService;
import com.sqlteacher.application.nl2sql.Nl2SqlService;
import com.sqlteacher.application.nl2sql.DefaultNl2SqlSafetyService;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MockApplicationServiceConfig {
    @Bean
    public SqlRiskAnalysisService sqlRiskAnalysisService() {
        return new MockSqlRiskAnalysisService();
    }

    @Bean
    public SqlExecutionService sqlExecutionService(SqlRiskAnalysisService sqlRiskAnalysisService) {
        return new MockSqlExecutionService(sqlRiskAnalysisService);
    }

    @Bean
    public DatabaseMetadataService databaseMetadataService() {
        return new MockDatabaseMetadataService();
    }

    @Bean
    public Nl2SqlService nl2SqlService() {
        return new MockNl2SqlService();
    }

    @Bean
    public LearningEventService learningEventService() {
        return new MockLearningEventService();
    }

    @Bean
    public Nl2SqlSafetyService nl2SqlSafetyService(
        Nl2SqlService nl2SqlService,
        SqlRiskAnalysisService sqlRiskAnalysisService,
        LearningEventService learningEventService
    ) {
        return new DefaultNl2SqlSafetyService(
            nl2SqlService,
            sqlRiskAnalysisService,
            learningEventService
        );
    }

    @Bean
    public AiStatusService mockAiStatusService() {
        return new MockAiStatusService();
    }

    @Bean
    public ApplicationExceptionMapper applicationExceptionMapper() {
        return new DefaultApplicationExceptionMapper();
    }
}
