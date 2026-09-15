package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.metadata.DatabaseColumn;
import com.sqlteacher.application.metadata.DatabaseForeignKey;
import com.sqlteacher.application.metadata.DatabaseIndex;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                    // SQLite 的 TEXT/复合主键会生成 sqlite_autoindex_* 内部索引；
                    // 驱动的 getTables 不过滤它们，后续 getColumns 会报 Table not found。
                    if (tableName == null || tableName.startsWith("sqlite_")) {
                        continue;
                    }
                    String catalog = tableResult.getString("TABLE_CAT");
                    String schema = tableResult.getString("TABLE_SCHEM");

                    List<DatabaseColumn> columns =
                            loadColumns(metaData, catalog, schema, tableName);

                    List<DatabaseIndex> indexes =
                            loadIndexes(metaData, catalog, schema, tableName);

                    List<DatabaseForeignKey> foreignKeys =
                            loadForeignKeys(metaData, catalog, schema, tableName);

                    tables.add(new DatabaseTable(tableName, columns, indexes, foreignKeys));
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

    private List<DatabaseIndex> loadIndexes(
            DatabaseMetaData metaData,
            String catalog,
            String schema,
            String tableName
    ) {
        // 部分方言/权限下 getIndexInfo 会抛错；索引属增强信息，单表失败降级为空列表，不让整棵 schema 失败。
        Map<String, List<String>> columnsByIndex = new LinkedHashMap<>();
        Map<String, Boolean> uniquenessByIndex = new HashMap<>();
        try (ResultSet rs = metaData.getIndexInfo(
                catalog, schema, tableName, false, true)) {
            while (rs.next()) {
                short type = rs.getShort("TYPE");
                if (type == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                if (indexName == null || indexName.isBlank() || columnName == null) {
                    continue;
                }
                // SQLite 为 TEXT/复合主键生成 sqlite_autoindex_* 内部索引；主键已在列上标记，展示属噪音。
                if (indexName.startsWith("sqlite_autoindex_")) {
                    continue;
                }
                columnsByIndex.computeIfAbsent(indexName, key -> new ArrayList<>()).add(columnName);
                uniquenessByIndex.putIfAbsent(indexName, !rs.getBoolean("NON_UNIQUE"));
            }
        } catch (SQLException | RuntimeException e) {
            log.debug("Index metadata unavailable for table {}: {}", tableName, e.toString());
            return List.of();
        }
        List<DatabaseIndex> indexes = new ArrayList<>();
        columnsByIndex.forEach((indexName, columns) -> indexes.add(new DatabaseIndex(
                indexName,
                Boolean.TRUE.equals(uniquenessByIndex.get(indexName)),
                columns
        )));
        return indexes;
    }

    /**
     * v3.5.0 SCH-1: imported keys per table. 外键属增强信息，部分方言/权限下
     * getImportedKeys 行为不一，单表失败降级为空列表，不让整棵结构树失败
     * （沿用 CXN-2 索引降级模式）；演示库/SQLite 为主要验证对象。
     */
    private List<DatabaseForeignKey> loadForeignKeys(
            DatabaseMetaData metaData,
            String catalog,
            String schema,
            String tableName
    ) {
        List<FkRow> rows = new ArrayList<>();
        try (ResultSet rs = metaData.getImportedKeys(catalog, schema, tableName)) {
            while (rs.next()) {
                String column = rs.getString("FKCOLUMN_NAME");
                String referencedTable = rs.getString("PKTABLE_NAME");
                String referencedColumn = rs.getString("PKCOLUMN_NAME");
                if (column == null || column.isBlank()
                        || referencedTable == null || referencedColumn == null) {
                    continue;
                }
                rows.add(new FkRow(
                        rs.getInt("KEY_SEQ"),
                        column,
                        referencedTable,
                        referencedColumn,
                        rs.getString("FK_NAME")));
            }
        } catch (SQLException | RuntimeException error) {
            log.debug("Foreign key metadata unavailable for table {}: {}", tableName, error.toString());
            return List.of();
        }
        return groupForeignKeys(rows);
    }

    /**
     * 命名约束按 FK_NAME 聚合；未命名（SQLite 不提供约束名）按连续段聚合：KEY_SEQ
     * 回退或引用表变化即视为新约束。段内按 KEY_SEQ 排序，保证列与引用列按位对齐。
     */
    private static List<DatabaseForeignKey> groupForeignKeys(List<FkRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<DatabaseForeignKey> keys = new ArrayList<>();
        int index = 0;
        while (index < rows.size()) {
            FkRow first = rows.get(index);
            List<FkRow> group = new ArrayList<>();
            if (first.fkName() != null && !first.fkName().isBlank()) {
                String constraint = first.fkName();
                while (index < rows.size() && constraint.equals(rows.get(index).fkName())) {
                    group.add(rows.get(index++));
                }
            } else {
                String referencedTable = first.referencedTable();
                int previousSeq = Integer.MIN_VALUE;
                while (index < rows.size()) {
                    FkRow row = rows.get(index);
                    boolean unnamed = row.fkName() == null || row.fkName().isBlank();
                    if (!unnamed || row.seq() <= previousSeq
                            || !row.referencedTable().equals(referencedTable)) {
                        break;
                    }
                    group.add(row);
                    previousSeq = row.seq();
                    index++;
                }
            }
            group.sort(Comparator.comparingInt(FkRow::seq));
            keys.add(new DatabaseForeignKey(
                    group.stream().map(FkRow::column).toList(),
                    group.getFirst().referencedTable(),
                    group.stream().map(FkRow::referencedColumn).toList()));
        }
        return List.copyOf(keys);
    }

    private record FkRow(int seq, String column, String referencedTable, String referencedColumn, String fkName) {
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
