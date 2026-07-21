package com.sqlteacher.domain.exercise;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExerciseDomainContractTest {
    @Test
    void shouldNormalizeKeywordsAndDefensivelyCopyHints() {
        List<String> hints = new ArrayList<>(List.of("先确定需要的列", "再添加筛选条件"));
        ExerciseDefinition exercise = new ExerciseDefinition(
            "select-basic-01",
            "查询学生姓名",
            "返回所有学生姓名。",
            "SELECT 列",
            ExerciseDifficulty.BEGINNER,
            "student-basic-v1",
            "SELECT name FROM student ORDER BY id",
            new ExerciseEvaluationRule(true, true, true, null, List.of(" select ", "SELECT")),
            hints,
            1,
            true,
            Instant.EPOCH,
            Instant.EPOCH
        );

        hints.clear();

        assertEquals(List.of("SELECT"), exercise.evaluationRule().requiredSqlKeywords());
        assertEquals(2, exercise.hints().size());
        assertThrows(UnsupportedOperationException.class, () -> exercise.hints().add("answer"));
    }

    @Test
    void shouldRejectInvalidRulesAndVersions() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExerciseEvaluationRule(false, false, false, null, List.of())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExerciseEvaluationRule(true, false, true, null, List.of())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExerciseDataset("dataset", "Dataset", "CREATE TABLE sample(id INTEGER)", 0)
        );
    }
}
