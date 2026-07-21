package com.sqlteacher.infrastructure.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

final class ExercisePackageCodec {
    static final int CURRENT_PACKAGE_VERSION = 1;

    private final ObjectMapper mapper = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    String encode(List<ExerciseDataset> datasets, List<ExerciseDefinition> exercises) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                new PackageData(
                    CURRENT_PACKAGE_VERSION,
                    datasets.stream().map(DatasetData::from).toList(),
                    exercises.stream().map(ExerciseData::from).toList()
                )
            );
        } catch (JsonProcessingException error) {
            throw new SqlTeacherException("EXERCISE_EXPORT_FAILED", "Failed to encode exercise package", error);
        }
    }

    DecodedPackage decode(String json) {
        if (json == null || json.isBlank()) {
            throw new SqlTeacherException("EXERCISE_IMPORT_INVALID", "Exercise package must not be blank");
        }
        try {
            PackageData data = mapper.readValue(json, PackageData.class);
            if (data.packageVersion() != CURRENT_PACKAGE_VERSION) {
                throw new SqlTeacherException(
                    "EXERCISE_IMPORT_VERSION_UNSUPPORTED",
                    "Unsupported exercise package version: " + data.packageVersion()
                );
            }
            List<ExerciseDataset> datasets = requireList(data.datasets(), "datasets").stream()
                .map(DatasetData::toDomain)
                .toList();
            List<ExerciseDefinition> exercises = requireList(data.exercises(), "exercises").stream()
                .map(ExerciseData::toDomain)
                .toList();
            rejectDuplicateIds(datasets.stream().map(ExerciseDataset::id).toList(), "dataset");
            rejectDuplicateIds(exercises.stream().map(ExerciseDefinition::id).toList(), "exercise");
            return new DecodedPackage(datasets, exercises);
        } catch (SqlTeacherException error) {
            throw error;
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException error) {
            throw new SqlTeacherException("EXERCISE_IMPORT_INVALID", "Invalid exercise package", error);
        }
    }

    String encodeRule(ExerciseEvaluationRule rule) {
        try {
            return mapper.writeValueAsString(RuleData.from(rule));
        } catch (JsonProcessingException error) {
            throw new SqlTeacherException("EXERCISE_DATA_INVALID", "Failed to encode evaluation rule", error);
        }
    }

    ExerciseEvaluationRule decodeRule(String json) {
        try {
            return mapper.readValue(json, RuleData.class).toDomain();
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException error) {
            throw new SqlTeacherException("EXERCISE_DATA_INVALID", "Invalid stored evaluation rule", error);
        }
    }

    String encodeHints(List<String> hints) {
        try {
            return mapper.writeValueAsString(hints);
        } catch (JsonProcessingException error) {
            throw new SqlTeacherException("EXERCISE_DATA_INVALID", "Failed to encode hints", error);
        }
    }

    List<String> decodeHints(String json) {
        try {
            return List.copyOf(mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        } catch (JsonProcessingException | NullPointerException error) {
            throw new SqlTeacherException("EXERCISE_DATA_INVALID", "Invalid stored hints", error);
        }
    }

    private static <T> List<T> requireList(List<T> values, String name) {
        return List.copyOf(Objects.requireNonNull(values, name + " must not be null"));
    }

    private static void rejectDuplicateIds(List<String> ids, String type) {
        if (ids.stream().distinct().count() != ids.size()) {
            throw new SqlTeacherException("EXERCISE_IMPORT_INVALID", "Duplicate " + type + " IDs in package");
        }
    }

    record DecodedPackage(List<ExerciseDataset> datasets, List<ExerciseDefinition> exercises) {
        DecodedPackage {
            datasets = List.copyOf(datasets);
            exercises = List.copyOf(exercises);
        }
    }

    private record PackageData(int packageVersion, List<DatasetData> datasets, List<ExerciseData> exercises) {
    }

    private record DatasetData(String id, String name, String setupSql, int version) {
        static DatasetData from(ExerciseDataset dataset) {
            return new DatasetData(dataset.id(), dataset.name(), dataset.setupSql(), dataset.version());
        }

        ExerciseDataset toDomain() {
            return new ExerciseDataset(id, name, setupSql, version);
        }
    }

    private record ExerciseData(
        String id,
        String title,
        String description,
        String knowledgePoint,
        ExerciseDifficulty difficulty,
        String datasetId,
        String referenceSql,
        RuleData evaluationRule,
        List<String> hints,
        int version,
        boolean enabled,
        String createdAt,
        String updatedAt
    ) {
        static ExerciseData from(ExerciseDefinition exercise) {
            return new ExerciseData(
                exercise.id(), exercise.title(), exercise.description(), exercise.knowledgePoint(),
                exercise.difficulty(), exercise.datasetId(), exercise.referenceSql(),
                RuleData.from(exercise.evaluationRule()), exercise.hints(), exercise.version(),
                exercise.enabled(), exercise.createdAt().toString(), exercise.updatedAt().toString()
            );
        }

        ExerciseDefinition toDomain() {
            return new ExerciseDefinition(
                id, title, description, knowledgePoint, difficulty, datasetId, referenceSql,
                evaluationRule.toDomain(), hints, version, enabled,
                Instant.parse(createdAt), Instant.parse(updatedAt)
            );
        }
    }

    private record RuleData(
        boolean compareColumns,
        boolean compareRows,
        boolean rowOrderMatters,
        Integer expectedRowCount,
        List<String> requiredSqlKeywords
    ) {
        static RuleData from(ExerciseEvaluationRule rule) {
            return new RuleData(
                rule.compareColumns(), rule.compareRows(), rule.rowOrderMatters(),
                rule.expectedRowCount(), rule.requiredSqlKeywords()
            );
        }

        ExerciseEvaluationRule toDomain() {
            return new ExerciseEvaluationRule(
                compareColumns, compareRows, rowOrderMatters, expectedRowCount, requiredSqlKeywords
            );
        }
    }
}
