package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.application.config.AppConfigurationService;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.infrastructure.ai.OllamaAiStatusService;
import com.sqlteacher.infrastructure.config.PropertiesAppConfigurationService;
import com.sqlteacher.infrastructure.config.SqlTeacherProperties;
import com.sqlteacher.infrastructure.database.SqliteAppDatabaseInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SqlTeacherApplicationConfig {
    @Bean
    public AppConfigurationService appConfigurationService() {
        return new PropertiesAppConfigurationService();
    }

    @Bean
    public SqlTeacherProperties sqlTeacherProperties(AppConfigurationService appConfigurationService) {
        return appConfigurationService.current();
    }

    @Bean
    public DatabaseInitializationService databaseInitializationService(SqlTeacherProperties properties) {
        return new SqliteAppDatabaseInitializer(properties);
    }

    @Bean
    public AiStatusService aiStatusService(SqlTeacherProperties properties) {
        return new OllamaAiStatusService(properties.ai());
    }
}
