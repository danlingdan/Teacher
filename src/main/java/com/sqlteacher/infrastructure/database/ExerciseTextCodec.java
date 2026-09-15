package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseChapterPath;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseEvaluationRule;
import com.sqlteacher.domain.exercise.ExerciseRevealMode;
import com.sqlteacher.domain.exercise.ExerciseType;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExerciseTextCodec {
    static final int FORMAT_VERSION = 1;

    private static final String FORMAT_MARKER = "# SQLTeacherExercisePackage";
    private static final String DATASET_HEADER = "===[DATASET]===";
    private static final String EXERCISE_HEADER = "===[EXERCISE]===";
    // v3.5.0 EPATH-1：章节路径块。旧客户端不认识这个头部时会把其中的行当噪音拒绝，
    // 但同步通道只下发各自清单里的块，旧清单没有 PATH 引用，旧客户端永远不会见到它。
    private static final String PATH_HEADER = "===[PATH]===";
    private static final Pattern MARKER_PATTERN = Pattern.compile("^# SQLTeacherExercisePackage\\s+(\\d+)$");
    private static final Pattern LABEL_PATTERN = Pattern.compile("^([A-Z][A-Z0-9_]*):(.*)$");
    /** CHAPTERS 行格式：序号|章节名|知识点标签（逗号分隔）|题目 ID（逗号分隔）。 */
    private static final Pattern CHAPTER_LINE_PATTERN = Pattern.compile("(\\d+)\\|([^|]+)\\|([^|]*)\\|(.*)");

    private static final Set<String> DATASET_LABELS = Set.of("ID", "NAME", "VERSION", "SQL");
    private static final Set<String> EXERCISE_LABELS = Set.of(
        "ID", "TITLE", "KNOWLEDGE", "DIFFICULTY", "DATASET", "DESCRIPTION", "SQL",
        "RULE", "COMPARE_COLUMNS", "COMPARE_ROWS", "ROW_ORDER", "EXPECTED_ROWS",
        "KEYWORDS", "HINTS", "VERSION", "ENABLED", "CREATED", "UPDATED",
        "TYPE", "VERIFY", "ALLOWED", "AFFECTED", "TXN", "PROBE",
        "WEIGHTS", "REVEAL", "EXPECT_COLUMNS", "PLAN_KEYWORDS"
    );
    private static final Set<String> PATH_LABELS = Set.of("ID", "NAME", "VERSION", "CHAPTERS");
    private static final Set<String> MULTILINE_LABELS = Set.of("SQL", "DESCRIPTION", "HINTS", "VERIFY", "PROBE", "CHAPTERS");

    String encode(List<ExerciseDataset> datasets, List<ExerciseDefinition> exercises) {
        StringBuilder out = new StringBuilder();
        out.append(FORMAT_MARKER).append(' ').append(FORMAT_VERSION).append('\n');
        for (ExerciseDataset dataset : datasets) {
            encodeDataset(out, dataset);
        }
        for (ExerciseDefinition exercise : exercises) {
            encodeExercise(out, exercise);
        }
        return out.toString();
    }

    /** Encodes a single dataset as one standalone block (used for bank distribution). */
    String encodeDatasetBlock(ExerciseDataset dataset) {
        StringBuilder out = new StringBuilder();
        encodeDataset(out, dataset);
        return out.toString();
    }

    /** Encodes a single exercise as one standalone block (used for bank distribution). */
    String encodeExerciseBlock(ExerciseDefinition exercise) {
        StringBuilder out = new StringBuilder();
        encodeExercise(out, exercise);
        return out.toString();
    }

    /** v3.5.0 EPATH-1：把章节路径编码为独立分发块。 */
    String encodePathBlock(ExerciseChapterPath path) {
        StringBuilder out = new StringBuilder();
        out.append('\n').append(PATH_HEADER).append('\n')
            .append("ID: ").append(path.id()).append('\n')
            .append("NAME: ").append(path.name()).append('\n')
            .append("VERSION: ").append(path.version()).append('\n')
            .append("CHAPTERS:\n");
        for (ExerciseChapterPath.Chapter chapter : path.chapters()) {
            out.append(chapter.order()).append('|').append(chapter.title()).append('|')
                .append(String.join(",", chapter.knowledgeTags())).append('|')
                .append(String.join(",", chapter.exerciseIds())).append('\n');
        }
        return out.toString();
    }

    private void encodeDataset(StringBuilder out, ExerciseDataset dataset) {
        out.append('\n').append(DATASET_HEADER).append('\n')
            .append("ID: ").append(dataset.id()).append('\n')
            .append("NAME: ").append(dataset.name()).append('\n')
            .append("VERSION: ").append(dataset.version()).append('\n')
            .append("SQL:\n").append(dataset.setupSql()).append('\n');
    }

    private void encodeExercise(StringBuilder out, ExerciseDefinition exercise) {
        ExerciseEvaluationRule rule = exercise.evaluationRule();
        out.append('\n').append(EXERCISE_HEADER).append('\n')
            .append("ID: ").append(exercise.id()).append('\n')
            .append("TITLE: ").append(exercise.title()).append('\n')
            .append("KNOWLEDGE: ").append(exercise.knowledgePoint()).append('\n')
            .append("DIFFICULTY: ").append(exercise.difficulty().name()).append('\n')
            .append("DATASET: ").append(exercise.datasetId()).append('\n');
        if (exercise.exerciseType() != ExerciseType.QUERY) {
            out.append("TYPE: ").append(exercise.exerciseType().name()).append('\n');
        }
        out.append("DESCRIPTION:\n").append(exercise.description()).append('\n')
            .append("SQL:\n").append(exercise.referenceSql()).append('\n');
        if (exercise.verificationSql() != null) {
            out.append("VERIFY:\n").append(exercise.verificationSql()).append('\n');
        }
        if (!exercise.allowedStatementTypes().isEmpty()) {
            out.append("ALLOWED: ")
                .append(String.join(", ", exercise.allowedStatementTypes())).append('\n');
        }
        if (exercise.expectedAffectedRows() != null) {
            out.append("AFFECTED: ").append(exercise.expectedAffectedRows()).append('\n');
        }
        if (!exercise.requiredTransactionKeywords().isEmpty()) {
            out.append("TXN: ")
                .append(String.join(", ", exercise.requiredTransactionKeywords())).append('\n');
        }
        if (exercise.triggerProbeSql() != null) {
            out.append("PROBE:\n").append(exercise.triggerProbeSql()).append('\n');
        }
        if (!exercise.expectedColumns().isEmpty()) {
            out.append("EXPECT_COLUMNS: ")
                .append(String.join(", ", exercise.expectedColumns())).append('\n');
        }
        if (exercise.revealMode() != ExerciseRevealMode.ON_FAIL) {
            out.append("REVEAL: ").append(exercise.revealMode().name()
                .toLowerCase(Locale.ROOT).replace('_', '-')).append('\n');
        }
        out.append("COMPARE_COLUMNS: ").append(rule.compareColumns()).append('\n')
            .append("COMPARE_ROWS: ").append(rule.compareRows()).append('\n')
            .append("ROW_ORDER: ").append(rule.rowOrderMatters()).append('\n');
        if (rule.expectedRowCount() != null) {
            out.append("EXPECTED_ROWS: ").append(rule.expectedRowCount()).append('\n');
        }
        if (!rule.requiredSqlKeywords().isEmpty()) {
            out.append("KEYWORDS: ").append(String.join(", ", rule.requiredSqlKeywords())).append('\n');
        }
        if (!rule.criterionWeights().isEmpty()) {
            out.append("WEIGHTS: ").append(rule.criterionWeights().entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "))).append('\n');
        }
        if (!rule.planKeywords().isEmpty()) {
            out.append("PLAN_KEYWORDS: ").append(String.join(", ", rule.planKeywords())).append('\n');
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
        List<ExerciseChapterPath> paths = new ArrayList<>();
        for (Block block : blocks) {
            try {
                if (block.type == BlockType.DATASET) {
                    datasets.add(toDataset(block));
                } else if (block.type == BlockType.PATH) {
                    paths.add(toPath(block));
                } else {
                    exercises.add(toExercise(block));
                }
            } catch (IllegalArgumentException error) {
                throw invalid("Invalid block content: " + error.getMessage(), block.startLine);
            }
        }
        rejectDuplicateIds(datasets.stream().map(ExerciseDataset::id).toList(), "dataset");
        rejectDuplicateIds(exercises.stream().map(ExerciseDefinition::id).toList(), "exercise");
        rejectDuplicateIds(paths.stream().map(ExerciseChapterPath::id).toList(), "path");
        return new DecodedPackage(datasets, exercises, paths);
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
            if (DATASET_HEADER.equals(trimmed) || EXERCISE_HEADER.equals(trimmed) || PATH_HEADER.equals(trimmed)) {
                BlockType type = DATASET_HEADER.equals(trimmed) ? BlockType.DATASET
                    : PATH_HEADER.equals(trimmed) ? BlockType.PATH : BlockType.EXERCISE;
                current = new Block(type, lineNumber);
                blocks.add(current);
                continue;
            }
            Matcher label = LABEL_PATTERN.matcher(trimmed);
            if (label.matches()) {
                if (current == null) {
                    throw invalid("Content appears before any block", lineNumber);
                }
                String name = label.group(1);
                Set<String> allowed = current.type == BlockType.DATASET ? DATASET_LABELS
                    : current.type == BlockType.PATH ? PATH_LABELS : EXERCISE_LABELS;
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

    /** v3.5.0 EPATH-1：解析章节路径块；CHAPTERS 每行为「序号|章节名|标签|题目ID」。 */
    private static ExerciseChapterPath toPath(Block block) {
        String id = required(block, "ID");
        String name = required(block, "NAME");
        int version = intField(block, "VERSION", 1);
        Field chaptersField = find(block, "CHAPTERS");
        if (chaptersField == null || chaptersField.value.isBlank()) {
            throw invalid("Path block requires CHAPTERS", block.startLine);
        }
        List<ExerciseChapterPath.Chapter> chapters = new ArrayList<>();
        for (String line : chaptersField.value.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher chapter = CHAPTER_LINE_PATTERN.matcher(trimmed);
            if (!chapter.matches()) {
                throw invalid("CHAPTERS lines must be 序号|章节名|标签|题目ID", block.startLine);
            }
            chapters.add(new ExerciseChapterPath.Chapter(
                Integer.parseInt(chapter.group(1)),
                chapter.group(2).trim(),
                splitCsv(chapter.group(3)),
                splitCsv(chapter.group(4))
            ));
        }
        return new ExerciseChapterPath(id, name, version, chapters);
    }

    private static List<String> splitCsv(String value) {
        List<String> values = new ArrayList<>();
        for (String part : value.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                values.add(item);
            }
        }
        return values;
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
        ExerciseType type = exerciseType(block);
        String verificationSql = optionalMultiline(block, "VERIFY");
        List<String> allowedTypes = keywordList(block, "ALLOWED");
        Integer affectedRows = optionalInt(block, "AFFECTED");
        List<String> transactionKeywords = keywordList(block, "TXN");
        String triggerProbeSql = optionalMultiline(block, "PROBE");
        List<String> expectedColumns = keywordList(block, "EXPECT_COLUMNS");
        ExerciseRevealMode revealMode = revealMode(block);
        rule = withRuleExtensions(block, rule);
        return new ExerciseDefinition(
            id, title, description, knowledgePoint, difficulty, datasetId, referenceSql,
            rule, hints, version, enabled, createdAt, updatedAt,
            type, verificationSql, allowedTypes, affectedRows, transactionKeywords, triggerProbeSql,
            expectedColumns, revealMode
        );
    }

    private static ExerciseRevealMode revealMode(Block block) {
        Field field = find(block, "REVEAL");
        if (field == null || field.value.isBlank()) {
            return ExerciseRevealMode.ON_FAIL;
        }
        try {
            return ExerciseRevealMode.parse(field.value);
        } catch (IllegalArgumentException error) {
            throw invalid("Unknown reveal mode: " + field.value, field.line);
        }
    }

    /** Applies WEIGHTS/PLAN_KEYWORDS on top of the parsed base rule. */
    private static ExerciseEvaluationRule withRuleExtensions(Block block, ExerciseEvaluationRule base) {
        Field weightsField = find(block, "WEIGHTS");
        Field planField = find(block, "PLAN_KEYWORDS");
        if (weightsField == null && planField == null) {
            return base;
        }
        Map<String, Integer> weights = new java.util.LinkedHashMap<>();
        if (weightsField != null && !weightsField.value.isBlank()) {
            for (String part : weightsField.value.split(",")) {
                String entry = part.trim();
                if (entry.isEmpty()) {
                    continue;
                }
                int separator = entry.indexOf(':');
                if (separator <= 0 || separator == entry.length() - 1) {
                    throw invalid("WEIGHTS entries must be criterion:weight", weightsField.line);
                }
                try {
                    weights.put(entry.substring(0, separator).trim(),
                        Integer.valueOf(entry.substring(separator + 1).trim()));
                } catch (NumberFormatException error) {
                    throw invalid("WEIGHTS entries must be criterion:weight", weightsField.line);
                }
            }
        }
        List<String> planKeywords = planField == null || planField.value.isBlank()
            ? List.of()
            : java.util.Arrays.stream(planField.value.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        return new ExerciseEvaluationRule(
            base.compareColumns(), base.compareRows(), base.rowOrderMatters(),
            base.expectedRowCount(), base.requiredSqlKeywords(), weights, planKeywords
        );
    }

    private static ExerciseType exerciseType(Block block) {
        Field field = find(block, "TYPE");
        if (field == null || field.value.isBlank()) {
            return ExerciseType.QUERY;
        }
        try {
            return ExerciseType.valueOf(field.value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw invalid("Unknown exercise type: " + field.value, field.line);
        }
    }

    private static String optionalMultiline(Block block, String label) {
        Field field = find(block, label);
        if (field == null) {
            return null;
        }
        String value = field.value.strip();
        return value.isEmpty() ? null : value;
    }

    private static List<String> keywordList(Block block, String label) {
        Field field = find(block, label);
        if (field == null || field.value.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : field.value.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
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
        return new ExerciseEvaluationRule(compareColumns, compareRows, rowOrderMatters, expectedRowCount, keywords,
            Map.of(), List.of());
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

    record DecodedPackage(List<ExerciseDataset> datasets, List<ExerciseDefinition> exercises,
                          List<ExerciseChapterPath> paths) {
        DecodedPackage {
            datasets = List.copyOf(datasets);
            exercises = List.copyOf(exercises);
            paths = List.copyOf(paths);
        }

        DecodedPackage(List<ExerciseDataset> datasets, List<ExerciseDefinition> exercises) {
            this(datasets, exercises, List.of());
        }
    }

    private enum BlockType { DATASET, EXERCISE, PATH }

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
