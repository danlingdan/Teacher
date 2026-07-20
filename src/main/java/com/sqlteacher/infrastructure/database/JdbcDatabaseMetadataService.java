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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class JdbcDatabaseMetadataService implements DatabaseMetadataService{
    private static final Logger log = LoggerFactory.getLogger(JdbcDatabaseMetadataService.class);
    private static final String[] TABLE_TYPES = {"TABLE", "VIEW", "SYSTEM TABLE", "SYSTEM VIEW"};

    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(5);
    private final JdbcConnectionProvider connectionProvider;

    public JdbcDatabaseMetadataService(JdbcConnectionProvider connectionProvider) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider);
    }

    @Override
    public List<DatabaseTable> listTables(String connectionId) {

        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId must not be blank");
        }

        log.debug("Loading database metadata for connection: {}", connectionId);

        try (Connection connection = connectionProvider.open(connectionId, METADATA_TIMEOUT)) {

            DatabaseMetaData metaData = connection.getMetaData();
            List<DatabaseTable> tables = new ArrayList<>();
            String currentCatalog = blankToNull(connection.getCatalog());
            String currentSchema = blankToNull(connection.getSchema());

            try (ResultSet tableResult = metaData.getTables(currentCatalog, currentSchema, "%", TABLE_TYPES)) {

                while (tableResult.next()) {

                    String tableName = tableResult.getString("TABLE_NAME");
                    String catalog = tableResult.getString("TABLE_CAT");
                    String schema = tableResult.getString("TABLE_SCHEM");

                    List<DatabaseColumn> columns =
                            loadColumns(metaData, catalog, schema, tableName);

                    tables.add(new DatabaseTable(tableName, columns));
                }
            }

            return tables;

        } catch (SQLException e) {
            JdbcFailureClassifier.JdbcFailure failure = JdbcFailureClassifier.classify(e);
            String errorCode = failure == JdbcFailureClassifier.JdbcFailure.SQL
                ? "DATABASE_METADATA_FAILED"
                : failure.errorCode();
            String userMessage = failure == JdbcFailureClassifier.JdbcFailure.SQL
                ? "表结构读取失败，请检查数据库连接后重试。"
                : failure.userMessage();
            log.warn(
                "Database metadata failed, connectionId={}, failureType={}, sqlState={}, vendorCode={}",
                connectionId,
                failure,
                JdbcFailureClassifier.sqlState(e),
                JdbcFailureClassifier.vendorCode(e)
            );
            throw new SqlTeacherException(
                    errorCode,
                    userMessage
            );
        }
    }

    private List<DatabaseColumn> loadColumns(
            DatabaseMetaData metaData,
            String catalog,
            String schema,
            String tableName
    ) throws SQLException {

        Set<String> primaryKeys = loadPrimaryKeys(metaData, catalog, schema, tableName);

        List<DatabaseColumn> columns = new ArrayList<>();

        try (ResultSet columnResult =
                     metaData.getColumns(catalog, schema, tableName, "%")) {

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
            String catalog,
            String schema,
            String tableName
    ) throws SQLException {

        Set<String> primaryKeys = new HashSet<>();

        try (ResultSet rs =
                     metaData.getPrimaryKeys(catalog, schema, tableName)) {

            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        }

        return primaryKeys;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
