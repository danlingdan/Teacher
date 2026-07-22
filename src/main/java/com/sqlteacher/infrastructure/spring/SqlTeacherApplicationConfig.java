package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.application.ai.NetworkAiSettingsService;
import com.sqlteacher.application.config.AppConfigurationService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.CloudLearningSyncService;
import com.sqlteacher.application.event.LearningEventQueryService;
import com.sqlteacher.application.event.LearningEventRecorder;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.error.DefaultApplicationExceptionMapper;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.nl2sql.DefaultNl2SqlSafetyService;
import com.sqlteacher.application.nl2sql.Nl2SqlService;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.infrastructure.ai.Nl2SqlServiceImpl;
import com.sqlteacher.infrastructure.ai.OllamaAiModelProvider;
import com.sqlteacher.infrastructure.ai.OllamaAiStatusService;
import com.sqlteacher.infrastructure.ai.OllamaModelSelectionService;
import com.sqlteacher.infrastructure.ai.InMemoryNetworkAiSettingsService;
import com.sqlteacher.infrastructure.ai.SwitchableAiModelProvider;
import com.sqlteacher.infrastructure.config.PropertiesAppConfigurationService;
import com.sqlteacher.infrastructure.cloud.HttpCloudApiClient;
import com.sqlteacher.infrastructure.cloud.PersistentCloudSessionService;
import com.sqlteacher.infrastructure.cloud.WindowsDpapiCloudSessionStore;
import com.sqlteacher.infrastructure.cloud.DefaultCloudLearningSyncService;
import com.sqlteacher.infrastructure.database.DatabaseServiceConfig;
import com.sqlteacher.infrastructure.database.SqliteAppDatabaseInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.net.URI;

@Configuration
@Import(DatabaseServiceConfig.class)
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
    public AiModelProvider aiModelProvider(SqlTeacherConfiguration properties, AiStatusService aiStatusService,
            NetworkAiSettingsService networkSettings) {
        return new SwitchableAiModelProvider(new OllamaAiModelProvider(properties.ai(), aiStatusService), networkSettings);
    }

    @Bean(destroyMethod = "clear")
    public NetworkAiSettingsService networkAiSettingsService() { return new InMemoryNetworkAiSettingsService(); }

    @Bean
    public AiModelSelectionService aiModelSelectionService(SqlTeacherConfiguration properties) {
        return new OllamaModelSelectionService(
            properties.ai(),
            properties.dataDirectory().resolve("selected-ai-model.txt")
        );
    }

    @Bean
    public Nl2SqlService nl2SqlService(
        AiModelProvider aiModelProvider,
        SqlTeacherConfiguration properties,
        AiModelSelectionService modelSelectionService,
        DatabaseMetadataService databaseMetadataService,
        LearningEventService learningEventService,
        ConnectionManagementService connectionManagementService
    ) {
        return new Nl2SqlServiceImpl(
            aiModelProvider,
            properties.ai(),
            modelSelectionService,
            databaseMetadataService,
            learningEventService,
            connectionManagementService
        );
    }

    @Bean
    public Nl2SqlSafetyService nl2SqlSafetyService(
        Nl2SqlService nl2SqlService,
        SqlRiskAnalysisService riskAnalysisService,
        LearningEventService learningEventService
    ) {
        return new DefaultNl2SqlSafetyService(
            nl2SqlService,
            riskAnalysisService,
            learningEventService
        );
    }

    @Bean
    public ApplicationExceptionMapper applicationExceptionMapper() {
        return new DefaultApplicationExceptionMapper();
    }

    @Bean
    public CloudApiClient cloudApiClient() {
        return new HttpCloudApiClient(URI.create(System.getProperty(
            "sqlteacher.cloud.base-url", "https://api.sqlteacher.invalid"
        )));
    }

    @Bean
    public CloudSessionService cloudSessionService(SqlTeacherConfiguration configuration, CloudApiClient api) {
        return new PersistentCloudSessionService(
            new WindowsDpapiCloudSessionStore(configuration.dataDirectory().resolve("cloud-session.dat")), api
        );
    }

    @Bean
    public CloudLearningSyncService cloudLearningSyncService(CloudApiClient api, CloudSessionService sessions,
            LearningEventQueryService query, LearningEventRecorder recorder, SqlTeacherConfiguration configuration) {
        return new DefaultCloudLearningSyncService(api, sessions, query, recorder,
            configuration.dataDirectory().resolve("cloud-state"));
    }
}
