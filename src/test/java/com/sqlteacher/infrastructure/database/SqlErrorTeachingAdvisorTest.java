package com.sqlteacher.infrastructure.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlErrorTeachingAdvisorTest {
    /** v3.5.0 SFE-2：sqlite-jdbc 消息带 [SQLITE_*] 前缀，映射须大小写不敏感且提取 near 词。 */
    @Test
    void shouldExplainSyntaxErrorWithNearToken() {
        String note = SqlErrorTeachingAdvisor.advise(
            "[SQLITE_ERROR] SQL error or missing database (near \"FROMM\": syntax error)");
        assertTrue(note.contains("FROMM"));
        assertTrue(note.contains("语法"));
    }

    @Test
    void shouldExplainForeignKeyConstraint() {
        String note = SqlErrorTeachingAdvisor.advise(
            "[SQLITE_CONSTRAINT_FOREIGNKEY] A foreign key constraint failed");
        assertTrue(note.contains("外键"));
        assertTrue(note.contains("参照完整性"));
    }

    @Test
    void shouldExplainUniqueConstraintWithColumns() {
        String note = SqlErrorTeachingAdvisor.advise("UNIQUE constraint failed: sc.sno, sc.cno");
        assertTrue(note.contains("唯一"));
        assertTrue(note.contains("sc.sno、sc.cno"));
    }

    @Test
    void shouldExplainNotNullConstraintWithColumn() {
        String note = SqlErrorTeachingAdvisor.advise("NOT NULL constraint failed: student.sname");
        assertTrue(note.contains("非空"));
        assertTrue(note.contains("student.sname"));
    }

    @Test
    void shouldExplainCheckConstraint() {
        String note = SqlErrorTeachingAdvisor.advise("[SQLITE_CONSTRAINT_CHECK] A CHECK constraint failed");
        assertTrue(note.contains("CHECK"));
    }

    @Test
    void shouldExplainMissingTableAndColumn() {
        assertTrue(SqlErrorTeachingAdvisor.advise("no such table: studnet").contains("studnet"));
        assertTrue(SqlErrorTeachingAdvisor.advise("no such column: snamee").contains("snamee"));
    }

    /** 未知错误必须原样透传：不产出解读，不臆造原因。 */
    @Test
    void shouldPassThroughUnknownMessages() {
        assertEquals("", SqlErrorTeachingAdvisor.advise("some totally unknown failure"));
        assertEquals("", SqlErrorTeachingAdvisor.advise((String) null));
        assertEquals("", SqlErrorTeachingAdvisor.advise("  "));
    }

    @Test
    void shouldWalkCauseChainToRootMessage() {
        RuntimeException error = new RuntimeException("wrapper",
            new java.sql.SQLException("FOREIGN KEY constraint failed"));
        assertTrue(SqlErrorTeachingAdvisor.advise(error).contains("外键"));
    }

    @Test
    void shouldAppendAndExtractEmbeddedNote() {
        String original = "SQL 执行失败，请检查语法。";
        String withNote = SqlErrorTeachingAdvisor.append(original, "FOREIGN KEY constraint failed");
        assertTrue(withNote.startsWith(original));
        assertTrue(withNote.contains(SqlErrorTeachingAdvisor.MARKER.trim()));
        assertEquals("违反了外键约束（参照完整性）：写入的值在引用的表中不存在，或删除/修改的行仍被其他表引用。"
            + "常见原因：先插入父表（如 Student、Course）再插入子表（如 SC）；删除前先清理子表里的引用；"
            + "自引用表（如 Course 的先修课 Cpno）还要注意先修课程是否已存在。",
            SqlErrorTeachingAdvisor.embeddedNote(withNote));
        // 未命中映射时文本原样返回，不附加标记。
        assertEquals(original, SqlErrorTeachingAdvisor.append(original, "unknown failure"));
        assertFalse(SqlErrorTeachingAdvisor.embeddedNote(original).contains("外键"));
    }
}
