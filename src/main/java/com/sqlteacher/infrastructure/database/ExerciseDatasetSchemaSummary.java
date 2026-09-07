package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.SqlTeacherException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the student-facing schema summary by introspecting a real in-memory database
 * instead of parsing the setup SQL text, so the preview always matches what practice and
 * evaluation will actually create. Declared foreign keys are appended as readable
 * column-to-column relations, which also explains id/student_id-style join keys.
 */
final class ExerciseDatasetSchemaSummary {
    private static final String PLACEHOLDER = "暂无数据集字段说明";

    private ExerciseDatasetSchemaSummary() {
    }

    static String fromSetupSql(String setupSql) {
        if (setupSql == null || setupSql.isBlank()) {
            return PLACEHOLDER;
        }
        try {
            ExerciseDatasetSqlPolicy.validate(setupSql);
            SqliteDriver.ensureLoaded();
        } catch (SqlTeacherException | SQLException error) {
            return PLACEHOLDER;
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement statement = connection.createStatement()) {
                for (String sql : SqlScriptSplitter.split(setupSql)) {
                    statement.execute(sql);
                }
            }
            List<String> tables = new ArrayList<>();
            List<String> relations = new ArrayList<>();
            for (String table : userTableNames(connection)) {
                List<String> columns = stringColumnValues(connection, "pragma table_info(" + quote(table) + ")", "name");
                if (!columns.isEmpty()) {
                    tables.add(table + "（" + String.join("、", columns) + "）");
                }
                relations.addAll(foreignKeyRelations(connection, table));
            }
            if (tables.isEmpty()) {
                return PLACEHOLDER;
            }
            // 表结构与外键关系分行展示，预览无需横向滚动即可读到完整关系。
            StringBuilder summary = new StringBuilder(String.join("；", tables));
            if (!relations.isEmpty()) {
                summary.append('\n').append(String.join("；", relations));
            }
            return summary.toString();
        } catch (SQLException error) {
            return PLACEHOLDER;
        }
    }

    private static List<String> userTableNames(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "select name from sqlite_master where type = 'table' and name not like 'sqlite_%' order by rowid"
             )) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    private record ForeignKey(int ordinal, String relation) {
    }

    private static List<String> foreignKeyRelations(Connection connection, String table) throws SQLException {
        List<ForeignKey> foreignKeys = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "select id, \"from\", \"table\", \"to\" from pragma_foreign_key_list(" + quote(table) + ")"
             )) {
            while (rows.next()) {
                String from = rows.getString("from");
                String target = rows.getString("table");
                String to = rows.getString("to");
                String relation = table + "." + from + " → " + target + (to == null ? "" : "." + to);
                foreignKeys.add(new ForeignKey(rows.getInt("id"), relation));
            }
        }
        // SQLite 按声明逆序编号（最后声明的外键 id 最小），降序排列即恢复声明顺序。
        return foreignKeys.stream()
            .sorted(Comparator.comparingInt(ForeignKey::ordinal).reversed())
            .map(ForeignKey::relation)
            .toList();
    }

    private static List<String> stringColumnValues(Connection connection, String query, String columnLabel)
        throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                values.add(rows.getString(columnLabel));
            }
        }
        return values;
    }

    private static String quote(String identifier) {
        return "'" + identifier.replace("'", "''") + "'";
    }
}
