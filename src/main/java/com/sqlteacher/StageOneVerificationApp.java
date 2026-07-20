package com.sqlteacher;

import com.sqlteacher.application.ai.AiStatus;
import com.sqlteacher.application.ai.AiStatusService;
import com.sqlteacher.application.ai.AiModelSelection;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.database.DatabaseInitializationResult;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.infrastructure.spring.SqlTeacherApplicationConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public final class StageOneVerificationApp {
    private StageOneVerificationApp() {
    }

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(SqlTeacherApplicationConfig.class)) {
            DatabaseInitializationResult databaseResult =
                context.getBean(DatabaseInitializationService.class).initialize();
            AiStatus aiStatus = context.getBean(AiStatusService.class).checkStatus();
            AiModelSelection modelSelection =
                context.getBean(AiModelSelectionService.class).refresh();

            System.out.println("SQLTeacher stage 1 application verification");
            System.out.println("[PASS] Spring DI - application context started");
            System.out.println("[PASS] SQLite app database - " + databaseResult.appDatabasePath());
            System.out.println("[PASS] SQLite demo database - " + databaseResult.demoDatabasePath());
            System.out.printf(
                "[%s] Ollama status - %s%n",
                aiStatus.available() ? "PASS" : "WARNING",
                aiStatus.message()
            );
            System.out.printf(
                "[%s] Ollama models - installed=%s, selected=%s%n",
                modelSelection.hasSelection() ? "PASS" : "WARNING",
                modelSelection.installedModels(),
                modelSelection.hasSelection() ? modelSelection.selectedModel() : "none"
            );
        }
    }
}
