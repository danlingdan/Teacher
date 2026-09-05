package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExerciseTextCodec {
    static final int FORMAT_VERSION = 1;

    private static final String FORMAT_MARKER = "# SQLTeacherExercisePackage";
    private static final String DATASET_HEADER = "===[DATASET]===";
    private static final String EXERCISE_HEADER = "===[EXERCISE]===";
    private static final Pattern MARKER_PATTERN = Pattern.compile("^# SQLTeacherExercisePackage\\s+(\\d+)$");
    private static final Pattern LABEL_PATTERN = Pattern.compile("^([A-Z][A-Z0-9_]*):(.*)$");

    private static final Set<String> DATASET_LABELS = Set.of("ID", "NAME", "VERSION", "SQL");
    private static final Set<String> EXERCISE_LABELS = Set.of(
        "ID", "TITLE", "KNOWLEDGE", "DIFFICULTY", "DATASET", "DESCRIPTION", "SQL",
        "RULE", "COMPARE_COLUMNS", "COMPARE_ROWS", "ROW_ORDER", "EXPECTED_ROWS",
        "KEYWORDS", "HINTS", "VERSION", "ENABLED", "CREATED", "UPDATED"
    );
    private static final Set<String> MULTILINE_LABELS = Set.of("SQL", "DESCRIPTION", "HINTS");

    String encode(List<ExerciseDataset> datasets, List<ExerciseDefinition> exercises) {
        StringBuilder out = new StringBuilder();
        out.append(FORMAT_MARKER).append(' ').append(FORMAT_VERSION).append('\n');
        for (ExerciseDataset dataset : datasets) {
            out.append('\n').append(DATASET_HEADER).append('\n')
                .append("ID: ").append(dataset.id()).append('\n')
                .append("NAME: ").append(dataset.name()).append('\n')
                .append("VERSION: ").append(dataset.version()).append('\n')
                .append("SQL:\n").append(dataset.setupSql()).append('\n');
        }
        for (ExerciseDefinition exercise : exercises) {
            ExerciseEvaluationRule rule = exercise.evaluationRule();
            out.append('\n').append(EXERCISE_HEADER).append('\n')
                .append("ID: ").append(exercise.id()).append('\n')
                .append("TITLE: ").append(exercise.title()).append('\n')
                .append("KNOWLEDGE: ").append(exercise.knowledgePoint()).append('\n')
                .append("DIFFICULTY: ").append(exercise.difficulty().name()).append('\n')
                .append("DATASET: ").append(exercise.datasetId()).append('\n')
                .append("DESCRIPTION:\n").append(exercise.description()).append('\n')
                .append("SQL:\n").append(exercise.referenceSql()).append('\n')
                .append("COMPARE_COLUMNS: ").append(rule.compareColumns()).append('\n')
                .append("COMPARE_ROWS: ").append(rule.compareRows()).append('\n')
                .append("ROW_ORDER: ").append(rule.rowOrderMatters()).append('\n');
            if (rule.expectedRowCount() != null) {
                out.append("EXPECTED_ROWS: ").append(rule.expectedRowCount()).append('\n');
            }
            if (!rule.requiredSqlKeywords().isEmpty()) {
                out.append("KEYWORDS: ").append(String.join(", ", rule.requiredSqlKeywords())).append('\n');
            }
            if (!exercise.hints().isEmpty()) {
                out.append("HINTS:\n");
                for (String hint : exercise.hints()) {
                    out.append(hint).append('\n');
                }
            }
            out.append("VERSION: ").append(exercise.version()).append('\n')
                .append("ENABLED: ").append(exercise.enabled()).append('\n')
                .append("CREATED: ").append(exercise.createdAt()).append('\n')
                .append("UPDATED: ").append(exercise.updatedAt()).append('\n');
        }
        return out.toString();
    }

    DecodedPackage decode(String text) {
        if (text == null || text.isBlank()) {
            throw new SqlTeacherException("EXERCISE_IMPORT_INVALID", "Exercise package must not be blank");
        }
        List<String> lines = text.lines().toList();
        int start = 0;
        if (!lines.isEmpty()) {
            Matcher marker = MARKER_PATTERN.matcher(lines.get(0).trim());
            if (marker.matches()) {
                int version = Integer.parseInt(marker.group(1));
                if (version != FORMAT_VERSION) {
                    throw new SqlTeacherException(
                        "EXERCISE_IMPORT_VERSION_UNSUPPORTED",
                        "Unsupported exercise package version: " + version
                    );
                }
                start = 1;
            }
        }
        List<Block> blocks = tokenize(lines, start);
        List<ExerciseDataset> datasets = new ArrayList<>();
        List<ExerciseDefinition> exercises = new ArrayList<>();
        for (Block block : blocks) {
            try {
                if (block.type == BlockType.DATASET) {
                    datasets.add(toDataset(block));
                } else {
                    exercises.add(toExercise(block));
                }
            } catch (IllegalArgumentException error) {
                throw invalid("Invalid block content: " + error.getMessage(), block.startLine);
            }
        }
        rejectDuplicateIds(datasets.stream().map(ExerciseDataset::id).toList(), "dataset");
        rejectDuplicateIds(exercises.stream().map(ExerciseDefinition::id).toList(), "exercise");
        return new DecodedPackage(datasets, exercises);
    }

    private static List<Block> tokenize(List<String> lines, int start) {
        List<Block> blocks = new ArrayList<>();
        Block current = null;
        for (int i = start; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String raw = lines.get(i);
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (DATASET_HEADER.equals(trimmed) || EXERCISE_HEADER.equals(trimmed)) {
                current = new Block(
                    DATASET_HEADER.equals(trimmed) ? BlockType.DATASET : BlockType.EXERCISE,
                    lineNumber
                );
                blocks.add(current);
                continue;
            }
            Matcher label = LABEL_PATTERN.matcher(trimmed);
            if (label.matches()) {
                if (current == null) {
                    throw invalid("Content appears before any block", lineNumber);
                }
                String name = label.group(1);
                Set<String> allowed = current.type == BlockType.DATASET ? DATASET_LABELS : EXERCISE_LABELS;
                if (!allowed.contains(name)) {
                    throw invalid(
                        "Unknown field " + name + " in " + current.type.name().toLowerCase() + " block",
                        lineNumber
                    );
                }
                current.fields.add(new Field(name, label.group(2).trim(), lineNumber));
            } else {
                if (current == null) {
                    throw invalid("Content appears before any block", lineNumber);
                }
                if (current.fields.isEmpty()
                        || !MULTILINE_LABELS.contains(current.fields.get(current.fields.size() - 1).label)) {
                    throw invalid("Unexpected content in block", lineNumber);
                }
                Field last = current.fields.get(current.fields.size() - 1);
                if (last.value.isEmpty()) {
                    last.value = raw;
                } else {
                    last.value = last.value + "\n" + raw;
                }
            }
        }
        return blocks;
    }

    private static ExerciseDataset toDataset(Block block) {
        String id = required(block, "ID");
        String name = required(block, "NAME");
        String setupSql = required(block, "SQL");
        int version = intField(block, "VERSION", 1);
        return new ExerciseDataset(id, name, setupSql, version);
    }

    private static ExerciseDefinition toExercise(Block block) {
        String id = optional(block, "ID");
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        String title = required(block, "TITLE");
        String description = required(block, "DESCRIPTION");
        String knowledgePoint = required(block, "KNOWLEDGE");
        ExerciseDifficulty difficulty = difficulty(block);
        String datasetId = required(block, "DATASET");
        String referenceSql = required(block, "SQL");
        ExerciseEvaluationRule rule = evaluationRule(block);
        List<String> hints = hints(block);
        int version = intField(block, "VERSION", 1);
        boolean enabled = booleanField(block, "ENABLED", true);
        Instant now = Instant.now();
        Instant createdAt = instantField(block, "CREATED", now);
        Instant updatedAt = instantField(block, "UPDATED", now);
        return new ExerciseDefinition(
            id, title, description, knowledgePoint, difficulty, datasetId, referenceSql,
            rule, hints, version, enabled, createdAt, updatedAt
        );
    }

    private static ExerciseDifficulty difficulty(Block block) {
        String raw = required(block, "DIFFICULTY");
        try {
            return ExerciseDifficulty.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException error) {
            throw invalid("Unknown difficulty: " + raw, block.startLine);
        }
    }

    private static ExerciseEvaluationRule evaluationRule(Block block) {
        Field ruleField = find(block, "RULE");
        if (ruleField != null && !ruleField.value.isBlank()) {
            switch (ruleField.value.trim().toUpperCase()) {
                case "EXACT" -> { return ExerciseEvaluationRule.exactResult(false); }
                case "EXACT ORDER" -> { return ExerciseEvaluationRule.exactResult(true); }
                default -> throw invalid("Unknown rule: " + ruleField.value, ruleField.line);
            }
        }
        boolean compareColumns = booleanField(block, "COMPARE_COLUMNS", false);
        boolean compareRows = booleanField(block, "COMPARE_ROWS", false);
        boolean rowOrderMatters = booleanField(block, "ROW_ORDER", false);
        Integer expectedRowCount = optionalInt(block, "EXPECTED_ROWS");
        List<String> keywords = keywords(block);
        if (!compareColumns && !compareRows && expectedRowCount == null && keywords.isEmpty()) {
            return ExerciseEvaluationRule.exactResult(false);
        }
        return new ExerciseEvaluationRule(compareColumns, compareRows, rowOrderMatters, expectedRowCount, keywords);
    }

    private static List<String> hints(Block block) {
        Field field = find(block, "HINTS");
        if (field == null || field.value.isBlank()) {
            return List.of();
        }
        List<String> hints = new ArrayList<>();
        for (String line : field.value.split("\n")) {
            String hint = line.trim();
            if (!hint.isEmpty()) {
                hints.add(hint);
            }
        }
        return hints;
    }

    private static List<String> keywords(Block block) {
        Field field = find(block, "KEYWORDS");
        if (field == null || field.value.isBlank()) {
            return List.of();
        }
        List<String> keywords = new ArrayList<>();
        for (String part : field.value.split(",")) {
            String keyword = part.trim();
            if (!keyword.isEmpty()) {
                keywords.add(keyword);
            }
        }
        return keywords;
    }

    private static Field find(Block block, String label) {
        for (Field field : block.fields) {
            if (field.label.equals(label)) {
                return field;
            }
        }
        return null;
    }

    private static String required(Block block, String label) {
        Field field = find(block, label);
        if (field == null || field.value.isBlank()) {
            throw invalid("Missing required field " + label, block.startLine);
        }
        return field.value;
    }

    private static String optional(Block block, String label) {
        Field field = find(block, label);
        return field == null ? null : field.value;
    }

    private static int intField(Block block, String label, int defaultValue) {
        String raw = optional(block, label);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException error) {
            throw invalid("Field " + label + " must be an integer", block.startLine);
        }
    }

    private static Integer optionalInt(Block block, String label) {
        String raw = optional(block, label);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException error) {
            throw invalid("Field " + label + " must be an integer", block.startLine);
        }
    }

    private static boolean booleanField(Block block, String label, boolean defaultValue) {
        String raw = optional(block, label);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(raw.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw.trim())) {
            return false;
        }
        throw invalid("Field " + label + " must be true or false", block.startLine);
    }

    private static Instant instantField(Block block, String label, Instant defaultValue) {
        String raw = optional(block, label);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException error) {
            throw invalid("Field " + label + " must be an ISO-8601 timestamp", block.startLine);
        }
    }

    private static void rejectDuplicateIds(List<String> ids, String type) {
        if (ids.stream().distinct().count() != ids.size()) {
            throw new SqlTeacherException("EXERCISE_IMPORT_INVALID", "Duplicate " + type + " IDs in package");
        }
    }

    private static SqlTeacherException invalid(String message, int line) {
        return new SqlTeacherException(
            "EXERCISE_IMPORT_INVALID",
            line > 0 ? message + " (line " + line + ")" : message
        );
    }

    record DecodedPackage(List<ExerciseDataset> datasets, List<ExerciseDefinition> exercises) {
        DecodedPackage {
            datasets = List.copyOf(datasets);
            exercises = List.copyOf(exercises);
        }
    }

    private enum BlockType { DATASET, EXERCISE }

    private static final class Block {
        final BlockType type;
        final int startLine;
        final List<Field> fields = new ArrayList<>();

        Block(BlockType type, int startLine) {
            this.type = type;
            this.startLine = startLine;
        }
    }

    private static final class Field {
        final String label;
        String value;
        final int line;

        Field(String label, String value, int line) {
            this.label = label;
            this.value = value;
            this.line = line;
        }
    }
}
