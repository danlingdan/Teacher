package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.config.AppConfigurationService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.error.DefaultApplicationExceptionMapper;
import com.sqlteacher.infrastructure.config.PropertiesAppConfigurationService;
import com.sqlteacher.infrastructure.database.DatabaseServiceConfig;
import com.sqlteacher.infrastructure.database.SqliteAppDatabaseInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Assembly root for the desktop application context (v3.4.0 REF-20). Configuration loading,
 * database bootstrap, and error mapping live here; the subdomain configs are imported:
 * database-backed services (connection/execution core plus exercise, learning, knowledge,
 * collaboration), BYO-AI, grounded knowledge, cloud collaboration, and platform/system.
 * Assembly stays explicit — no component scanning, no field injection.
 */
@Configuration
@Import({
    DatabaseServiceConfig.class,
    DatabaseExerciseServiceConfig.class,
    DatabaseLearningServiceConfig.class,
    DatabaseKnowledgeServiceConfig.class,
    DatabaseCollaborationServiceConfig.class,
    AiServiceConfig.class,
    GroundedKnowledgeServiceConfig.class,
    CloudCollaborationServiceConfig.class,
    SystemServiceConfig.class
})
public class SqlTeacherApplicationConfig {
    static final String DEFAULT_CLOUD_BASE_URL = "https://api.sqlteacher.tech";

    @Bean
    public AppConfigurationService appConfigurationService() {
        return new PropertiesAppConfigurationService();
    }

    @Bean
    public SqlTeacherConfiguration sqlTeacherConfiguration(AppConfigurationService appConfigurationService) {
        return appConfigurationService.current();
    }

    @Bean
    public DatabaseInitializationService databaseInitializationService(SqlTeacherConfiguration properties) {
        return new SqliteAppDatabaseInitializer(properties);
    }

    @Bean
    public ApplicationExceptionMapper applicationExceptionMapper() {
        return new DefaultApplicationExceptionMapper();
    }
}
