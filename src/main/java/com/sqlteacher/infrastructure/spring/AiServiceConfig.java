package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.ai.AiContextPolicy;
import com.sqlteacher.application.ai.AiModelProvider;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.application.ai.AiTaskHistoryService;
import com.sqlteacher.application.ai.AiTaskService;
import com.sqlteacher.application.ai.AiUsagePolicy;
import com.sqlteacher.application.ai.NetworkAiSettingsService;
import com.sqlteacher.application.ai.AiProviderProbeService;
import com.sqlteacher.application.collaboration.FeedbackDraftEnhancer;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExerciseTextDraftingService;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.nl2sql.DefaultNl2SqlSafetyService;
import com.sqlteacher.application.nl2sql.Nl2SqlService;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.infrastructure.ai.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BYO-AI plumbing: model providers and selection, status/probing, task service and history,
 * usage/context policy, NL2SQL, exercise text drafting, and feedback enhancement
 * (v3.4.0 REF-20 split of SqlTeacherApplicationConfig).
 */
@Configuration
public class AiServiceConfig {

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
    public ExerciseTextDraftingService exerciseTextDraftingService(
        AiModelProvider provider,
        SqlTeacherConfiguration properties,
        AiModelSelectionService modelSelectionService,
        ExerciseManagementService exerciseManagementService
    ) {
        return new ExerciseTextDraftingServiceImpl(
            provider,
            properties.ai(),
            modelSelectionService,
            exerciseManagementService
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
}
