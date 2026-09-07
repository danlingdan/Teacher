package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;

import java.util.List;
import java.util.Optional;

/**
 * Public entry point for parsing and self-testing text-DSL exercise packages. It lets the
 * cloud server and the network bank sync validate distributed content through exactly the
 * same deterministic rules as teacher import, without widening the internal codec types.
 */
public final class ExerciseBankContent {
    private final ExerciseTextCodec textCodec = new ExerciseTextCodec();
    private final ExercisePackageValidator validator =
        new ExercisePackageValidator(new DefaultSqlRiskAnalysisService());

    /** Parsed and validated package content. */
    public record ParsedBank(List<ExerciseDataset> datasets, List<ExerciseDefinition> exercises) {
        public ParsedBank {
            datasets = List.copyOf(datasets);
            exercises = List.copyOf(exercises);
        }
    }

    public ParsedBank parse(String packageText) {
        ExerciseTextCodec.DecodedPackage decoded = textCodec.decode(packageText);
        return new ParsedBank(decoded.datasets(), decoded.exercises());
    }

    /** Returns the self-test failure messages; empty when the bank is ready for distribution. */
    public List<String> selfTestFailures(ParsedBank bank) {
        return selfTestFailures(bank, id -> Optional.empty());
    }

    /**
     * Same as {@link #selfTestFailures(ParsedBank)} but resolves datasets that the bank
     * references without redefining (delta updates keep unchanged datasets locally).
     */
    public List<String> selfTestFailures(
        ParsedBank bank, java.util.function.Function<String, Optional<ExerciseDataset>> storedDatasets
    ) {
        ExercisePackageValidator.Result result = validator.validate(
            bank.datasets(), bank.exercises(), storedDatasets
        );
        return result.failures();
    }

    public String encodeDatasetBlock(ExerciseDataset dataset) {
        return textCodec.encodeDatasetBlock(dataset);
    }

    public String encodeExerciseBlock(ExerciseDefinition exercise) {
        return textCodec.encodeExerciseBlock(exercise);
    }
}
