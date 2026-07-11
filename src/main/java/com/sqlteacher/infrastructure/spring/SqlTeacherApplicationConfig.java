package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.application.config.AppConfigurationService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.error.DefaultApplicationExceptionMapper;
import com.sqlteacher.application.nl2sql.Nl2SqlService;
import com.sqlteacher.infrastructure.ai.Nl2SqlServiceImpl;
import com.sqlteacher.infrastructure.ai.OllamaAiModelProvider;
import com.sqlteacher.infrastructure.ai.OllamaAiStatusService;
import com.sqlteacher.infrastructure.config.PropertiesAppConfigurationService;
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
    public SqlTeacherConfiguration sqlTeacherConfiguration(AppConfigurationService appConfigurationService) {
        return appConfigurationService.current();
    }

    @Bean
    public DatabaseInitializationService databaseInitializationService(SqlTeacherConfiguration properties) {
        return new SqliteAppDatabaseInitializer(properties);
    }

    @Bean
    public AiStatusService aiStatusService(SqlTeacherConfiguration properties) {
        return new OllamaAiStatusService(properties.ai());
    }

    @Bean
    public AiModelProvider aiModelProvider(SqlTeacherConfiguration properties, AiStatusService aiStatusService) {
        return new OllamaAiModelProvider(properties.ai(), aiStatusService);
    }

    @Bean
    public Nl2SqlService nl2SqlService(AiModelProvider aiModelProvider, SqlTeacherConfiguration properties) {
        return new Nl2SqlServiceImpl(aiModelProvider, properties.ai());
    }

    @Bean
    public ApplicationExceptionMapper applicationExceptionMapper() {
        return new DefaultApplicationExceptionMapper();
    }
}
