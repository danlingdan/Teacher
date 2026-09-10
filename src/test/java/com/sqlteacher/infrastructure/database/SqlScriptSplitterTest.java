package com.sqlteacher.infrastructure.database;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlScriptSplitterTest {
    @Test
    void shouldKeepTriggerBodyInOneStatement() {
        String script = """
            create table audit(id integer);
            create trigger trg after insert on student
            begin
              insert into audit values (new.id);
              update stats set total = total + 1;
            end;
            insert into audit values (1);
            """;

        List<String> statements = SqlScriptSplitter.split(script);

        assertEquals(3, statements.size());
        assertEquals("create table audit(id integer)", statements.get(0));
        assertTrue(statements.get(1).startsWith("create trigger trg"));
        assertTrue(statements.get(1).contains("update stats set total = total + 1;"));
        // The terminating semicolon belongs to the splitter, body-internal ones stay.
        assertTrue(statements.get(1).trim().endsWith("end"), statements.get(1));
        assertEquals("insert into audit values (1)", statements.get(2));
    }

    @Test
    void shouldSplitTransactionScriptNormally() {
        List<String> statements = SqlScriptSplitter.split("begin; insert into t values (1); commit;");

        assertEquals(List.of("begin", "insert into t values (1)", "commit"), statements);
    }

    @Test
    void shouldHandleTriggerWithoutTrailingSemicolon() {
        List<String> statements = SqlScriptSplitter.split(
            "create trigger trg before delete on t begin select 1; end");

        assertEquals(1, statements.size());
        assertTrue(statements.getFirst().trim().endsWith("end"));
    }

    @Test
    void shouldIgnoreKeywordsInsideLiteralsAndComments() {
        String script = """
            insert into t values ('end; create trigger x; begin;');
            -- create trigger fake begin select 1; end;
            /* create trigger fake2 begin select 1; end; */
            update t set a = 1;
            """;

        List<String> statements = SqlScriptSplitter.split(script);

        assertEquals(2, statements.size());
    }
}
