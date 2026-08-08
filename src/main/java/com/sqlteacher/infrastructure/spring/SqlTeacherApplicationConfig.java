package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.ai.*;
import com.sqlteacher.application.config.AppConfigurationService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.CloudLearningSyncService;
import com.sqlteacher.application.collaboration.FeedbackDraftEnhancer;
import com.sqlteacher.application.event.LearningEventQueryService;
import com.sqlteacher.application.event.LearningEventRecorder;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.error.DefaultApplicationExceptionMapper;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.GroundedKnowledgeExplanationService;
import com.sqlteacher.application.knowledge.HybridKnowledgeRetrievalService;
import com.sqlteacher.application.nl2sql.DefaultNl2SqlSafetyService;
import com.sqlteacher.application.nl2sql.Nl2SqlService;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.runner.LocalCodeWorkspaceLauncher;
import com.sqlteacher.application.runner.LocalCodeRunner;
import com.sqlteacher.application.support.DiagnosticBundleService;
import com.sqlteacher.application.support.ProblemReportService;
import com.sqlteacher.application.system.GeneralSoftwareService;
import com.sqlteacher.application.update.UpdateService;
import com.sqlteacher.infrastructure.ai.*;
import com.sqlteacher.infrastructure.config.PropertiesAppConfigurationService;
import com.sqlteacher.infrastructure.cloud.HttpCloudApiClient;
import com.sqlteacher.infrastructure.cloud.PersistentCloudSessionService;
import com.sqlteacher.infrastructure.cloud.WindowsDpapiCloudSessionStore;
import com.sqlteacher.infrastructure.security.WindowsDpapiSecretStore;
import com.sqlteacher.infrastructure.runner.WindowsLocalCodeWorkspaceLauncher;
import com.sqlteacher.infrastructure.runner.WindowsLocalIdeCodeRunner;
import com.sqlteacher.infrastructure.cloud.DefaultCloudLearningSyncService;
import com.sqlteacher.infrastructure.database.DatabaseServiceConfig;
import com.sqlteacher.infrastructure.database.SqliteAppDatabaseInitializer;
import com.sqlteacher.infrastructure.database.JdbcConnectionFactory;
import com.sqlteacher.infrastructure.database.JdbcGroundedTutorService;
import com.sqlteacher.infrastructure.support.FileDiagnosticBundleService;
import com.sqlteacher.infrastructure.support.HttpProblemReportService;
import com.sqlteacher.infrastructure.system.FileGeneralSoftwareService;
import com.sqlteacher.infrastructure.update.SecureUpdateService;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.planning.GroundedTutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.net.URI;

@Configuration
@Import(DatabaseServiceConfig.class)
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
    public AiStatusService aiStatusService(SqlTeacherConfiguration properties) {
        return new OllamaAiStatusService(properties.ai());
    }

    @Bean
    public AiModelProvider aiModelProvider(SqlTeacherConfiguration properties, AiStatusService aiStatusService,
            NetworkAiSettingsService networkSettings) {
        return new SwitchableAiModelProvider(new OllamaAiModelProvider(properties.ai(), aiStatusService), networkSettings);
    }

    @Bean
    public FeedbackDraftEnhancer feedbackDraftEnhancer(AiTaskService taskService, AiContextPolicy contextPolicy) {
        return new SafeAiFeedbackDraftEnhancer(taskService, contextPolicy);
    }

    @Bean(destroyMethod = "close")
    public PersistentNetworkAiSettingsService networkAiSettingsService(SqlTeacherConfiguration properties) {
        return new PersistentNetworkAiSettingsService(
            properties.dataDirectory().resolve("ai-providers.json"),
            properties.dataDirectory().resolve("ai-provider-keys")
        );
    }

    @Bean public AiProviderProbeService aiProviderProbeService() { return new HttpAiProviderProbeService(); }

    @Bean public AiContextPolicy aiContextPolicy() { return new DefaultAiContextPolicy(); }

    @Bean public AiTaskHistoryService aiTaskHistoryService(SqlTeacherConfiguration properties) {
        return new FileAiTaskHistoryService(properties.dataDirectory().resolve("ai-task-history.json"));
    }

    @Bean public AiUsagePolicy aiUsagePolicy() { return AiUsagePolicy.defaults(); }

    @Bean public AiTaskService aiTaskService(AiModelProvider provider, AiUsagePolicy usagePolicy,
            AiTaskHistoryService historyService) {
        return new DefaultAiTaskService(provider, usagePolicy, historyService);
    }

    @Bean
    public GroundedKnowledgeExplanationService groundedKnowledgeExplanationService(
        CourseKnowledgeService knowledgeService,
        HybridKnowledgeRetrievalService retrievalService,
        AiTaskService taskService,
        AiContextPolicy contextPolicy
    ) {
        return new DefaultGroundedKnowledgeExplanationService(knowledgeService, retrievalService, taskService, contextPolicy);
    }

    @Bean
    public GroundedTutorService groundedTutorService(GroundedKnowledgeExplanationService explanations,
                                                      JdbcConnectionFactory connections,
                                                      LearningEventOwnerProvider owners) {
        return new JdbcGroundedTutorService(explanations, connections, owners);
    }

    @Bean
    public AiModelSelectionService aiModelSelectionService(SqlTeacherConfiguration properties) {
        return new OllamaModelSelectionService(
            properties.ai(),
            properties.dataDirectory().resolve("selected-ai-model.txt")
        );
    }

    @Bean
    public Nl2SqlService nl2SqlService(
        AiTaskService aiTaskService,
        SqlTeacherConfiguration properties,
        AiModelSelectionService modelSelectionService,
        DatabaseMetadataService databaseMetadataService,
        LearningEventService learningEventService,
        ConnectionManagementService connectionManagementService,
        AiContextPolicy contextPolicy
    ) {
        return new Nl2SqlServiceImpl(
            aiTaskService,
            properties.ai(),
            modelSelectionService,
            databaseMetadataService,
            learningEventService,
            connectionManagementService,
            contextPolicy
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
    public URI cloudBaseUri() {
        return URI.create(System.getProperty(
            "sqlteacher.cloud.base-url", DEFAULT_CLOUD_BASE_URL
        ));
    }

    @Bean
    public LocalCodeWorkspaceLauncher localCodeWorkspaceLauncher(SqlTeacherConfiguration configuration) {
        return new WindowsLocalCodeWorkspaceLauncher(
            configuration.dataDirectory().resolve("local-code-workspaces")
        );
    }

    @Bean
    public LocalCodeRunner localCodeRunner(SqlTeacherConfiguration configuration) {
        return new WindowsLocalIdeCodeRunner(configuration.dataDirectory().resolve("local-code-runs"));
    }

    @Bean
    public CloudApiClient cloudApiClient(URI cloudBaseUri) {
        return new HttpCloudApiClient(cloudBaseUri);
    }

    @Bean public GeneralSoftwareService generalSoftwareService(SqlTeacherConfiguration configuration, URI cloudBaseUri) {
        return new FileGeneralSoftwareService(configuration.dataDirectory(), cloudBaseUri);
    }

    @Bean public DiagnosticBundleService diagnosticBundleService(SqlTeacherConfiguration configuration) {
        return new FileDiagnosticBundleService(configuration.dataDirectory());
    }

    @Bean public ProblemReportService problemReportService(SqlTeacherConfiguration configuration, URI cloudBaseUri,
                                                            GeneralSoftwareService generalSoftwareService) {
        return new HttpProblemReportService(cloudBaseUri, configuration.dataDirectory(), generalSoftwareService);
    }

    @Bean public UpdateService updateService(SqlTeacherConfiguration configuration, URI cloudBaseUri,
                                              GeneralSoftwareService generalSoftwareService) {
        return new SecureUpdateService(cloudBaseUri, configuration.dataDirectory(), generalSoftwareService);
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
