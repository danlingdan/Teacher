package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseCatalogService;
import com.sqlteacher.application.connection.DatabaseConnectionTestService;
import com.sqlteacher.application.connection.DatabaseCredentialSession;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.execution.SqlHistoryService;
import com.sqlteacher.application.knowledge.KnowledgeVectorStore;
import com.sqlteacher.application.maintenance.ApplicationBackupService;
import com.sqlteacher.application.maintenance.DataMaintenanceService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.risk.SqlSafetyModeService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.infrastructure.config.FileSqlSafetyModeService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Core local-SQL plumbing: connection factory, result mapping, SQL risk analysis and safety
 * mode, connection lifecycle, execution, history, metadata, and maintenance/backup.
 *
 * <p>Since v3.4.0 REF-20 the exercise/activity, learning, knowledge, and cloud-collaboration
 * beans live in the sibling subdomain configs under
 * {@code com.sqlteacher.infrastructure.spring}; {@code SqlTeacherApplicationConfig} imports
 * them together with this class.</p>
 */
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
    public SqlSafetyModeService sqlSafetyModeService(SqlTeacherConfiguration configuration) {
        return new FileSqlSafetyModeService(configuration.dataDirectory().resolve("sql-safety.properties"));
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

    @Bean
    public DatabaseCatalogService databaseCatalogService(JdbcConnectionFactory connectionFactory) {
        return new JdbcDatabaseCatalogService(connectionFactory, Duration.ofSeconds(5));
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
            SqlSafetyModeService safetyModeService,
            LearningEventService learningEventService) {
        return new JdbcSqlExecutionService(
            connectionProvider, resultMapper, riskAnalysisService, safetyModeService, learningEventService
        );
    }

    @Bean
    public DatabaseMetadataService databaseMetadataService(JdbcConnectionProvider connectionProvider) {
        return new JdbcDatabaseMetadataService(connectionProvider);
    }

    @Bean
    public SqlHistoryService sqlHistoryService(JdbcConnectionFactory connectionFactory,
            com.sqlteacher.application.event.LearningEventOwnerProvider ownerProvider) {
        return new JdbcSqlHistoryService(connectionFactory, ownerProvider);
    }

    @Bean
    public com.sqlteacher.application.event.LocalRecordOwnershipService localRecordOwnershipService(
            JdbcConnectionFactory connectionFactory,
            com.sqlteacher.application.event.LearningEventOwnerProvider ownerProvider) {
        return new JdbcLocalRecordOwnershipService(connectionFactory, ownerProvider);
    }

    @Bean
    public DataMaintenanceService dataMaintenanceService(JdbcConnectionFactory connectionFactory) {
        return new JdbcDataMaintenanceService(connectionFactory);
    }

    @Bean
    public ApplicationBackupService applicationBackupService(SqlTeacherConfiguration configuration,
                                                             KnowledgeVectorStore vectorStore) {
        // The callback clears the local vector index after a restore without coupling the
        // backup service to Lucene or KnowledgeVectorStore types.
        return new SqliteApplicationBackupService(configuration, vectorStore::clear);
    }
}
