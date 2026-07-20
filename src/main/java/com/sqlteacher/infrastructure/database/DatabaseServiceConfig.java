package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.event.DefaultLearningEventService;
import com.sqlteacher.application.event.LearningEventQueryService;
import com.sqlteacher.application.event.LearningEventRecorder;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseServiceConfig {

    @Bean
    public JdbcConnectionFactory jdbcConnectionFactory(SqlTeacherConfiguration configuration) {
        return new JdbcConnectionFactory(configuration.database());
    }

    @Bean
    public SqlResultMapper sqlResultMapper() {
        return new SqlResultMapper();
    }

    @Bean
    public SqlRiskAnalysisService sqlRiskAnalysisService() {
        return new DefaultSqlRiskAnalysisService();
    }

    @Bean
    public SqlExecutionService sqlExecutionService(
            JdbcConnectionFactory connectionFactory,
            SqlResultMapper resultMapper,
            SqlRiskAnalysisService riskAnalysisService,
            LearningEventService learningEventService) {
        return new JdbcSqlExecutionService(connectionFactory, resultMapper, riskAnalysisService, learningEventService);
    }

    @Bean
    public DatabaseMetadataService databaseMetadataService(JdbcConnectionFactory connectionFactory) {
        return new JdbcDatabaseMetadataService(connectionFactory);
    }

    @Bean
    public LearningEventRecorder learningEventRecorder(JdbcConnectionFactory connectionFactory) {
        return new JdbcLearningEventRecorder(connectionFactory);
    }

    @Bean
    public LearningEventService learningEventService(LearningEventRecorder learningEventRecorder) {
        return new DefaultLearningEventService(learningEventRecorder);
    }

    @Bean
    public LearningEventQueryService learningEventQueryService(JdbcConnectionFactory connectionFactory) {
        return new JdbcLearningEventQueryService(connectionFactory);
    }
}
