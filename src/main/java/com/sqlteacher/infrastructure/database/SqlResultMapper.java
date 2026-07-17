package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.execution.SqlExecutionResult;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SqlResultMapper {
    public SqlExecutionResult mapQueryResult(
            ResultSet resultSet,
            Duration duration,
            int maxRows
    ) throws SQLException {

        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        ResultSetMetaData metaData = resultSet.getMetaData();

        int columnCount = metaData.getColumnCount();

        for (int i = 1; i <= columnCount; i++) {
            columns.add(metaData.getColumnLabel(i));
        }

        boolean truncated = false;

        while (resultSet.next()) {

            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }

            Map<String, Object> row = new LinkedHashMap<>();

            for (int i = 1; i <= columnCount; i++) {
                row.put(
                        metaData.getColumnLabel(i),
                        resultSet.getObject(i)
                );
            }

            rows.add(row);
        }

        return new SqlExecutionResult(
                true,
                columns,
                rows,
                0,
                truncated,
                "SQL executed successfully",
                duration
        );
    }

    public SqlExecutionResult mapUpdateResult(
            int affectedRows,
            Duration duration
    ) {

        return new SqlExecutionResult(
                true,
                List.of(),
                List.of(),
                affectedRows,
                false,
                "SQL executed successfully",
                duration
        );
    }

}
