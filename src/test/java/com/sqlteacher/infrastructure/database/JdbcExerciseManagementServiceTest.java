package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.exercise.ExerciseDraft;
import com.sqlteacher.application.exercise.ExerciseImportPreview;
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

    @Test
    void shouldPreviewPackageWithoutPersistingAnything() {
        JdbcExerciseManagementService service = initialize(tempDir.resolve("preview"));
        service.save(draft("preview-a", "预览题", null, true));
        String text = service.exportPackage(List.of("preview-a"));

        ExerciseImportPreview preview = service.parsePackage(text);

        assertEquals(1, preview.exercises().size());
        assertEquals("预览题", preview.exercises().get(0).title());
        assertTrue(preview.exercises().get(0).selfTest().passed());
        assertEquals(1, preview.datasets().size());
        assertEquals(ExerciseBankApplier.DATASET_ID, preview.datasets().get(0).id());
        assertTrue(preview.datasets().get(0).selfTest().passed());
        assertTrue(service.findDefinition("preview-a").isPresent());
    }

    @Test
    void shouldRejectInvalidPackageTextOnPreview() {
        JdbcExerciseManagementService service = initialize(tempDir.resolve("preview-invalid"));

        assertThrows(SqlTeacherException.class, () -> service.parsePackage("plain text without blocks"));
    }

    @Test
    void shouldReportSelfTestFailureInPreviewAndRejectImport() {
        JdbcExerciseManagementService service = initialize(tempDir.resolve("preview-self-test"));
        String text = """
            ===[DATASET]===
            ID: self-test-ds
            NAME: 自测数据
            VERSION: 1
            SQL:
            create table t(id integer primary key, name text);
            insert into t values (1, 'a');

            ===[EXERCISE]===
            TITLE: 引用缺失表
            KNOWLEDGE: 测试
            DIFFICULTY: BEGINNER
            DATASET: self-test-ds
            DESCRIPTION:
            描述
            SQL:
            select name from missing_table
            RULE: EXACT
            VERSION: 1
            ENABLED: true
            """;

        ExerciseImportPreview preview = service.parsePackage(text);

        assertTrue(preview.datasets().get(0).selfTest().passed());
        assertFalse(preview.exercises().get(0).selfTest().passed());
        assertEquals("参考答案未能在数据集上执行", preview.exercises().get(0).selfTest().message());

        SqlTeacherException error = assertThrows(SqlTeacherException.class, () -> service.importPackage(text));
        assertEquals("EXERCISE_IMPORT_INVALID", error.errorCode());
        assertTrue(error.getMessage().contains("未通过导入自测"));
        assertTrue(service.listDatasets().stream().noneMatch(dataset -> dataset.id().equals("self-test-ds")));
    }

    @Test
    void shouldRejectPackageWhoseDatasetViolatesPolicyOnImport() {
        JdbcExerciseManagementService service = initialize(tempDir.resolve("import-policy"));
        String text = """
            ===[DATASET]===
            ID: policy-ds
            NAME: 违规数据
            VERSION: 1
            SQL:
            create table t(id integer);
            pragma foreign_keys = on;

            ===[EXERCISE]===
            TITLE: 引用违规数据集
            KNOWLEDGE: 测试
            DIFFICULTY: BEGINNER
            DATASET: policy-ds
            DESCRIPTION:
            描述
            SQL:
            select id from t
            RULE: EXACT
            VERSION: 1
            ENABLED: true
            """;

        SqlTeacherException error = assertThrows(SqlTeacherException.class, () -> service.importPackage(text));

        assertEquals("EXERCISE_IMPORT_INVALID", error.errorCode());
        assertTrue(error.getMessage().contains("语句类型不被允许"), error.getMessage());
    }

    @Test
    void shouldRejectSaveWithBrokenReferenceSql() {
        JdbcExerciseManagementService service = initialize(tempDir.resolve("save-broken"));
        ExerciseDraft broken = new ExerciseDraft(
            "", "坏题", "描述", "测试", ExerciseDifficulty.BEGINNER,
            ExerciseBankApplier.DATASET_ID, "select * from missing_table",
            ExerciseEvaluationRule.exactResult(true), List.of(), null, true
        );

        SqlTeacherException error = assertThrows(SqlTeacherException.class, () -> service.save(broken));

        assertEquals("EXERCISE_SAVE_INVALID", error.errorCode());
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
            ExerciseBankApplier.DATASET_ID,
            "select name from student order by id",
            ExerciseEvaluationRule.exactResult(true),
            List.of("先选择 name 列。"),
            expectedVersion,
            enabled
        );
    }
}
