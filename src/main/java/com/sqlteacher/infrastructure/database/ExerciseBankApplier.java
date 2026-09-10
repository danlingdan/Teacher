package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Applies the bundled exercise bank (a versioned text-DSL resource shipped with the app)
 * to the stored catalog. Content is self-tested before it is applied and written through
 * the shared versioned upsert writer. Any failure is logged and leaves the stored catalog
 * untouched so the local learning flow never depends on bank application success.
 */
final class ExerciseBankApplier {
    private static final Logger log = LoggerFactory.getLogger(ExerciseBankApplier.class);
    static final String DATASET_ID = "school-core-v2";
    /** Bundled bank packages, applied in order through the versioned upsert (W4.1). */
    private static final List<String> BANK_RESOURCES = List.of(
        "/exercise/exercise-bank.dsl",
        "/exercise/exercise-bank-spj.dsl"
    );

    private final ExerciseTextCodec textCodec = new ExerciseTextCodec();
    private final ExerciseBankWriter writer = new ExerciseBankWriter();
    private final ExercisePackageValidator validator =
        new ExercisePackageValidator(new DefaultSqlRiskAnalysisService());

    int apply(Connection connection) {
        int applied = 0;
        for (String resource : BANK_RESOURCES) {
            applied += applyResource(connection, resource);
        }
        return applied;
    }

    private int applyResource(Connection connection, String resource) {
        try {
            ExerciseTextCodec.DecodedPackage bank = textCodec.decode(readBankResource(resource));
            ExercisePackageValidator.Result validation = validator.validate(
                bank.datasets(), bank.exercises(), id -> Optional.empty()
            );
            if (!validation.passed()) {
                throw new SqlTeacherException(
                    "EXERCISE_BANK_INVALID",
                    "内置题库未通过自测：" + String.join("；", validation.failures())
                );
            }
            int applied = 0;
            for (ExerciseDataset dataset : bank.datasets()) {
                if (writer.upsertDataset(connection, dataset) == ExerciseBankWriter.DatasetOutcome.INSERTED) {
                    applied++;
                }
            }
            for (ExerciseDefinition exercise : bank.exercises()) {
                if (writer.upsertExercise(connection, exercise) != ExerciseBankWriter.ExerciseOutcome.SKIPPED) {
                    applied++;
                }
            }
            return applied;
        } catch (IOException | SQLException | RuntimeException error) {
            log.error("Failed to apply the bundled exercise bank {}; keeping the stored catalog", resource, error);
            return 0;
        }
    }

    private String readBankResource(String resource) throws IOException {
        try (InputStream stream = ExerciseBankApplier.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Bundled exercise bank resource is missing: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
