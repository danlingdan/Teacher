package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

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
    @Profile("!mock")
    public SqlRiskAnalysisService sqlRiskAnalysisService() {
        return new DefaultSqlRiskAnalysisService();
    }

    @Bean
    @Profile("!mock")
    public SqlExecutionService sqlExecutionService(
            JdbcConnectionFactory connectionFactory,
            SqlResultMapper resultMapper,
            SqlRiskAnalysisService riskAnalysisService) {
        return new JdbcSqlExecutionService(connectionFactory, resultMapper, riskAnalysisService);
    }

    @Bean
    @Profile("!mock")
    public DatabaseMetadataService databaseMetadataService(JdbcConnectionFactory connectionFactory) {
        return new JdbcDatabaseMetadataService(connectionFactory);
    }
}