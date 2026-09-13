package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.exercise.ExerciseType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests for the shared submission gate so every Decision branch is pinned
 * independently of the three callers (session runner, evaluator, package self-test).
 */
class ExerciseSubmissionGateTest {
    private final ExerciseSubmissionGate gate = new ExerciseSubmissionGate(new DefaultSqlRiskAnalysisService());

    @Test
    void shouldRejectBlankOrNullSubmissions() {
        assertFalse(gate.check(ExerciseType.STATE, List.of(), "   ").allowed());
        assertFalse(gate.check(ExerciseType.STATE, List.of(), null).allowed());
        assertFalse(gate.check(ExerciseType.SCRIPT, List.of(), "").allowed());
    }

    @Test
    void shouldAcceptSingleAllowedStatementForState() {
        var decision = gate.check(ExerciseType.STATE, List.of("INSERT"), "INSERT INTO student (id) VALUES (1)");
        assertTrue(decision.allowed());
        assertEquals(List.of("INSERT INTO student (id) VALUES (1)"), decision.statements());
    }

    @Test
    void shouldRejectStateWithMultipleStatementsOrWrongType() {
        assertFalse(gate.check(ExerciseType.STATE, List.of("INSERT"),
            "INSERT INTO student (id) VALUES (1); INSERT INTO student (id) VALUES (2)").allowed());
        assertFalse(gate.check(ExerciseType.STATE, List.of("INSERT"), "DELETE FROM student").allowed());
        // FORBIDDEN class stays hard-blocked even when the teacher declared the type.
        assertFalse(gate.check(ExerciseType.STATE, List.of("CREATE"),
            "CREATE USER demo_user IDENTIFIED BY 'placeholder'").allowed());
    }

    @Test
    void shouldAcceptOnlySingleExecutableTriggerDefinition() {
        assertTrue(gate.check(ExerciseType.TRIGGER, List.of(),
            "CREATE TRIGGER t AFTER INSERT ON student BEGIN UPDATE student SET score = score; END").allowed());
        assertFalse(gate.check(ExerciseType.TRIGGER, List.of(),
            "UPDATE student SET score = 1").allowed());
        assertFalse(gate.check(ExerciseType.TRIGGER, List.of(),
            "CREATE TABLE t (id INTEGER)").allowed());
    }

    @Test
    void shouldAllowTransactionControlInsideScriptButRejectDisallowedTypes() {
        var decision = gate.check(ExerciseType.SCRIPT, List.of(),
            "BEGIN;\nINSERT INTO student (id) VALUES (1);\nCOMMIT");
        assertTrue(decision.allowed());
        assertEquals(3, decision.statements().size());

        var rejected = gate.check(ExerciseType.SCRIPT, List.of(), "GRANT ALL ON student TO demo_role");
        assertFalse(rejected.allowed());
        assertTrue(rejected.reason().contains("第 1 条语句"), rejected.reason());
    }

    @Test
    void shouldRejectForbiddenStatementInsideScript() {
        var rejected = gate.check(ExerciseType.SCRIPT, List.of(),
            "INSERT INTO student (id) VALUES (1);\nATTACH DATABASE 'aux.db' AS aux");
        assertFalse(rejected.allowed());
        assertTrue(rejected.reason().contains("第 2 条语句"), rejected.reason());
    }

    @Test
    void shouldRejectScriptExceedingStatementLimit() {
        String insert = "INSERT INTO student (id) VALUES (1)";
        StringBuilder script = new StringBuilder();
        for (int index = 0; index <= ExerciseSubmissionGate.MAX_SCRIPT_STATEMENTS; index++) {
            script.append(insert).append(";\n");
        }
        assertFalse(gate.check(ExerciseType.SCRIPT, List.of(), script.toString()).allowed());
    }
}
