package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.activity.ActivityEvaluationDispatcher;
import com.sqlteacher.application.activity.ActivityEvaluator;
import com.sqlteacher.application.activity.ActivityLearningService;
import com.sqlteacher.application.activity.ActivityReviewService;
import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.application.ai.NetworkAiSettingsService;
import com.sqlteacher.application.collaboration.CloudAccountApi;
import com.sqlteacher.application.collaboration.CloudAdminApi;
import com.sqlteacher.application.collaboration.CloudAuthApi;
import com.sqlteacher.application.collaboration.CloudBankApi;
import com.sqlteacher.application.collaboration.CloudCapabilityApi;
import com.sqlteacher.application.collaboration.CloudClassroomApi;
import com.sqlteacher.application.collaboration.CloudLearningSyncService;
import com.sqlteacher.application.collaboration.CloudPlanningApi;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.CloudSyncApi;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.config.AppConfigurationService;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionTestService;
import com.sqlteacher.application.course.CourseMapService;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExercisePracticeService;
import com.sqlteacher.application.exercise.SqlExerciseEvaluationService;
import com.sqlteacher.application.exercise.ExerciseProgressService;
import com.sqlteacher.application.event.LearningEventRecorder;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.runner.CodeRunner;
import com.sqlteacher.application.runner.LocalCodeRunner;
import com.sqlteacher.infrastructure.cloud.HttpCloudApiClient;
import com.sqlteacher.infrastructure.database.JdbcLearningEventRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("integration")
class SqlTeacherApplicationConfigTest {
    @Test
    void shouldUseProductionCloudApiByDefault() {
        assertEquals("https://api.sqlteacher.tech", SqlTeacherApplicationConfig.DEFAULT_CLOUD_BASE_URL);
    }

    @Test
    void shouldCreateStageOneApplicationBeans() {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(SqlTeacherApplicationConfig.class)) {
            assertNotNull(context.getBean(AppConfigurationService.class));
            assertNotNull(context.getBean(DatabaseInitializationService.class));
            assertNotNull(context.getBean(AiStatusService.class));
            assertNotNull(context.getBean(AiModelSelectionService.class));
            assertNotNull(context.getBean(ConnectionManagementService.class));
            assertNotNull(context.getBean(DatabaseConnectionTestService.class));
            assertNotNull(context.getBean(ApplicationExceptionMapper.class));
            assertNotNull(context.getBean(SqlExecutionService.class));
            assertNotNull(context.getBean(ExerciseManagementService.class));
            assertNotNull(context.getBean(ExerciseCatalogService.class));
            assertNotNull(context.getBean(CourseMapService.class));
            assertNotNull(context.getBean(SqlExerciseEvaluationService.class));
            assertNotNull(context.getBean(ActivityEvaluationDispatcher.class));
            assertNotNull(context.getBean(ActivityLearningService.class));
            assertNotNull(context.getBean(ActivityReviewService.class));
            assertEquals(8, context.getBeansOfType(ActivityEvaluator.class).size());
            assertEquals(2, context.getBeansOfType(CodeRunner.class).size());
            assertNotNull(context.getBean(LocalCodeRunner.class));
            assertNotNull(context.getBean(ExercisePracticeService.class));
            assertNotNull(context.getBean(ExerciseProgressService.class));
            assertNotNull(context.getBean(DatabaseMetadataService.class));
            assertNotNull(context.getBean(SqlRiskAnalysisService.class));
            assertNotNull(context.getBean(Nl2SqlSafetyService.class));
            assertNotNull(context.getBean(NetworkAiSettingsService.class));
            // v3.4.0 REF-4: one HttpCloudApiClient instance backs every narrow cloud port.
            assertNotNull(context.getBean(HttpCloudApiClient.class));
            assertNotNull(context.getBean(CloudCapabilityApi.class));
            assertNotNull(context.getBean(CloudAuthApi.class));
            assertNotNull(context.getBean(CloudAccountApi.class));
            assertNotNull(context.getBean(CloudClassroomApi.class));
            assertNotNull(context.getBean(CloudPlanningApi.class));
            assertNotNull(context.getBean(CloudBankApi.class));
            assertNotNull(context.getBean(CloudSyncApi.class));
            assertNotNull(context.getBean(CloudAdminApi.class));
            assertSame(context.getBean(CloudClassroomApi.class), context.getBean(CloudAuthApi.class));
            assertNotNull(context.getBean(CloudSessionService.class));
            assertNotNull(context.getBean(CloudLearningSyncService.class));
            assertInstanceOf(
                JdbcLearningEventRecorder.class,
                context.getBean(LearningEventRecorder.class)
            );
        }
    }
}
