package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.error.DefaultApplicationExceptionMapper;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.mock.MockAiStatusService;
import com.sqlteacher.application.mock.MockDatabaseMetadataService;
import com.sqlteacher.application.mock.MockNl2SqlService;
import com.sqlteacher.application.mock.MockSqlExecutionService;
import com.sqlteacher.application.mock.MockSqlRiskAnalysisService;
import com.sqlteacher.application.nl2sql.Nl2SqlService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class MockApplicationServiceConfig {
    @Bean
    @Profile("mock")
    public SqlRiskAnalysisService sqlRiskAnalysisService() {
        return new MockSqlRiskAnalysisService();
    }

    @Bean
    @Profile("mock")
    public SqlExecutionService sqlExecutionService(SqlRiskAnalysisService sqlRiskAnalysisService) {
        return new MockSqlExecutionService(sqlRiskAnalysisService);
    }

    @Bean
    @Profile("mock")
    public DatabaseMetadataService databaseMetadataService() {
        return new MockDatabaseMetadataService();
    }

    @Bean
    public Nl2SqlService nl2SqlService() {
        return new MockNl2SqlService();
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
