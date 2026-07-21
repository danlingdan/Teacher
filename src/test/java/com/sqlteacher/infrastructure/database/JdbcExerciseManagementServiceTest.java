package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.exercise.ExerciseDraft;
import com.sqlteacher.application.exercise.ExerciseImportResult;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcExerciseManagementServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldSeedTwentyExercisesAndKeepInitializationIdempotent() {
        JdbcExerciseManagementService service = initialize(tempDir.resolve("catalog"));
        initialize(tempDir.resolve("catalog"));

        assertEquals(20, service.listExercises(false).size());
        assertEquals(1, service.listDatasets().size());
    }

    @Test
    void shouldCreateUpdateCopyAndDisableWithVersionChecks() {
        JdbcExerciseManagementService service = initialize(tempDir.resolve("crud"));
        ExerciseDefinition created = service.save(draft("custom-crud", "自定义题目", null, true));
        ExerciseDefinition updated = service.save(draft(created.id(), "修改后的题目", created.version(), true));
        ExerciseDefinition copy = service.copy(updated.id(), "题目副本");

        assertEquals(2, updated.version());
        assertFalse(copy.enabled());
        assertEquals(1, copy.version());
        assertThrows(
            SqlTeacherException.class,
            () -> service.setEnabled(updated.id(), false, 1)
        );

        ExerciseDefinition disabled = service.setEnabled(updated.id(), false, updated.version());
        assertFalse(disabled.enabled());
        assertEquals(3, disabled.version());
    }

    @Test
    void shouldExportAndAtomicallyImportVersionedPackage() {
        JdbcExerciseManagementService source = initialize(tempDir.resolve("source"));
        source.save(draft("atomic-a-new", "待回滚的新题", null, true));
        source.save(draft("atomic-z-conflict", "冲突题", null, true));
        String packageJson = source.exportPackage(List.of("atomic-a-new", "atomic-z-conflict"));

        JdbcExerciseManagementService target = initialize(tempDir.resolve("target"));
        target.save(draft("atomic-z-conflict", "目标中已存在", null, true));

        assertThrows(SqlTeacherException.class, () -> target.importPackage(packageJson));
        assertTrue(target.findDefinition("atomic-a-new").isEmpty());

        String singlePackage = source.exportPackage(List.of("atomic-a-new"));
        ExerciseImportResult imported = target.importPackage(singlePackage);
        assertEquals(0, imported.datasetsImported());
        assertEquals(List.of("atomic-a-new"), imported.importedExerciseIds());
        assertTrue(target.findDefinition("atomic-a-new").isPresent());
    }

    private JdbcExerciseManagementService initialize(Path directory) {
        Path appDb = directory.resolve("app.db");
        DatabaseConfiguration databases = new DatabaseConfiguration(appDb, directory.resolve("demo.db"));
        SqlTeacherConfiguration configuration = new SqlTeacherConfiguration(
            "SQLTeacher",
            directory,
            databases,
            new AiConfiguration(
                URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(2), "test"
            )
        );
        new SqliteAppDatabaseInitializer(configuration).initialize();
        return new JdbcExerciseManagementService(new JdbcConnectionFactory(databases));
    }

    private static ExerciseDraft draft(String id, String title, Integer expectedVersion, boolean enabled) {
        return new ExerciseDraft(
            id,
            title,
            "返回所有学生姓名。",
            "自定义查询",
            ExerciseDifficulty.BEGINNER,
            DefaultExerciseCatalogSeeder.DATASET_ID,
            "select name from student order by id",
            ExerciseEvaluationRule.exactResult(true),
            List.of("先选择 name 列。"),
            expectedVersion,
            enabled
        );
    }
}
