package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionTestService;
import com.sqlteacher.application.connection.DatabaseCredentialSession;
import com.sqlteacher.application.event.DefaultLearningEventService;
import com.sqlteacher.application.event.LearningEventQueryService;
import com.sqlteacher.application.event.LearningEventRecorder;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

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
    public ConnectionManagementService connectionManagementService(
            JdbcConnectionFactory connectionFactory,
            SqlTeacherConfiguration configuration) {
        return new JdbcConnectionManagementService(connectionFactory, configuration.database());
    }

    @Bean
    public DatabaseConnectionTestService databaseConnectionTestService(
            JdbcConnectionFactory connectionFactory) {
        return new JdbcDatabaseConnectionTestService(connectionFactory, Duration.ofSeconds(5));
    }

    @Bean(destroyMethod = "close")
    public DatabaseCredentialSession databaseCredentialSession() {
        return new InMemoryDatabaseCredentialSession();
    }

    @Bean
    public JdbcConnectionProvider jdbcConnectionProvider(
            JdbcConnectionFactory connectionFactory,
            ConnectionManagementService connectionManagementService,
            DatabaseCredentialSession credentialSession) {
        return new ProfileAwareJdbcConnectionProvider(
            connectionFactory,
            connectionManagementService,
            credentialSession
        );
    }

    @Bean
    public SqlExecutionService sqlExecutionService(
            JdbcConnectionProvider connectionProvider,
            SqlResultMapper resultMapper,
            SqlRiskAnalysisService riskAnalysisService,
            LearningEventService learningEventService) {
        return new JdbcSqlExecutionService(connectionProvider, resultMapper, riskAnalysisService, learningEventService);
    }

    @Bean
    public DatabaseMetadataService databaseMetadataService(JdbcConnectionProvider connectionProvider) {
        return new JdbcDatabaseMetadataService(connectionProvider);
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

    @Bean
    public ExerciseManagementService exerciseManagementService(JdbcConnectionFactory connectionFactory) {
        return new JdbcExerciseManagementService(connectionFactory);
    }

    @Bean
    public ExerciseCatalogService exerciseCatalogService(ExerciseManagementService managementService) {
        return new JdbcExerciseCatalogService(managementService);
    }
}
