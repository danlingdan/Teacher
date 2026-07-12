package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.metadata.DatabaseColumn;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.metadata.DatabaseTable;
import com.sqlteacher.domain.SqlTeacherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class JdbcDatabaseMetadataService implements DatabaseMetadataService{
    private static final Logger log = LoggerFactory.getLogger(JdbcDatabaseMetadataService.class);

    private final JdbcConnectionFactory connectionFactory;

    public JdbcDatabaseMetadataService(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
    }

    @Override
    public List<DatabaseTable> listTables(String connectionId) {

        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId must not be blank");
        }

        log.debug("Loading database metadata for connection: {}", connectionId);

        try (Connection connection = connectionFactory.open(connectionId)) {

            DatabaseMetaData metaData = connection.getMetaData();
            List<DatabaseTable> tables = new ArrayList<>();

            try (ResultSet tableResult = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {

                while (tableResult.next()) {

                    String tableName = tableResult.getString("TABLE_NAME");

                    List<DatabaseColumn> columns =
                            loadColumns(metaData, tableName);

                    tables.add(new DatabaseTable(tableName, columns));
                }
            }

            return tables;

        } catch (SQLException e) {
            log.error("Failed to load database metadata for connection: {}", connectionId, e);
            throw new SqlTeacherException(
                    "DATABASE_METADATA_FAILED",
                    "Failed to load database metadata:"+ e.getMessage(),
                    e
            );
        }
    }

    private List<DatabaseColumn> loadColumns(
            DatabaseMetaData metaData,
            String tableName
    ) throws SQLException {

        Set<String> primaryKeys = loadPrimaryKeys(metaData, tableName);

        List<DatabaseColumn> columns = new ArrayList<>();

        try (ResultSet columnResult =
                     metaData.getColumns(null, null, tableName, "%")) {

            while (columnResult.next()) {

                String columnName = columnResult.getString("COLUMN_NAME");

                String typeName = columnResult.getString("TYPE_NAME");

                boolean nullable =
                        columnResult.getInt("NULLABLE")
                                == DatabaseMetaData.columnNullable;

                boolean primaryKey =
                        primaryKeys.contains(columnName);

                columns.add(
                        new DatabaseColumn(
                                columnName,
                                typeName,
                                nullable,
                                primaryKey
                        )
                );
            }
        }

        return columns;
    }

    private Set<String> loadPrimaryKeys(
            DatabaseMetaData metaData,
            String tableName
    ) throws SQLException {

        Set<String> primaryKeys = new HashSet<>();

        try (ResultSet rs =
                     metaData.getPrimaryKeys(null, null, tableName)) {

            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        }

        return primaryKeys;
    }
}
