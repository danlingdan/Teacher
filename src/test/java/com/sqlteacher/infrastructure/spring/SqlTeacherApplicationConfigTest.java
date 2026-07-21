package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.application.ai.NetworkAiSettingsService;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudLearningSyncService;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.config.AppConfigurationService;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionTestService;
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
import com.sqlteacher.infrastructure.database.JdbcLearningEventRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SqlTeacherApplicationConfigTest {
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
            assertNotNull(context.getBean(SqlExerciseEvaluationService.class));
            assertNotNull(context.getBean(ExercisePracticeService.class));
            assertNotNull(context.getBean(ExerciseProgressService.class));
            assertNotNull(context.getBean(DatabaseMetadataService.class));
            assertNotNull(context.getBean(SqlRiskAnalysisService.class));
            assertNotNull(context.getBean(Nl2SqlSafetyService.class));
            assertNotNull(context.getBean(NetworkAiSettingsService.class));
            assertNotNull(context.getBean(CloudApiClient.class));
            assertNotNull(context.getBean(CloudSessionService.class));
            assertNotNull(context.getBean(CloudLearningSyncService.class));
            assertInstanceOf(
                JdbcLearningEventRecorder.class,
                context.getBean(LearningEventRecorder.class)
            );
        }
    }
}
