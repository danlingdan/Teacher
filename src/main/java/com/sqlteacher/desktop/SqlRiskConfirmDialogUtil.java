package com.sqlteacher.desktop;

import com.sqlteacher.desktop.controller.SqlRiskConfirmDialogController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高危SQL二次确认弹窗全局工具类。
 *
 * <p>提供统一的风险判定与弹窗调用接口，全页面可复用。
 *
 * <p><b>风险判定规则</b>（满足任意一条自动触发弹窗）：
 * <ul>
 *   <li>DROP、TRUNCATE、ALTER、RENAME、DROP TABLE、DROP DATABASE 类删改表/库语句；</li>
 *   <li>所有 DELETE 语句（无论是否带 WHERE 条件）；</li>
 *   <li>无 WHERE 过滤条件的 UPDATE 全表更新语句；</li>
 *   <li>分号分隔多段批量执行 SQL；</li>
 * </ul>
 */
public final class SqlRiskConfirmDialogUtil {

    private static final Logger log = LoggerFactory.getLogger(SqlRiskConfirmDialogUtil.class);

    private static final Pattern DROP_PATTERN = Pattern.compile("\\bDROP\\s+(TABLE|DATABASE|INDEX|VIEW|SCHEMA)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRUNCATE_PATTERN = Pattern.compile("\\bTRUNCATE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_PATTERN = Pattern.compile("\\bALTER\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RENAME_PATTERN = Pattern.compile("\\bRENAME\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_PATTERN = Pattern.compile("\\bDELETE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_PATTERN = Pattern.compile("\\bINSERT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_PATTERN = Pattern.compile("\\bUPDATE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHERE_PATTERN = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEMICOLON_PATTERN = Pattern.compile(";\\s*$", Pattern.MULTILINE);

    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("\\bFROM\\s+([`'\"]?)([^`'\"\\s,;]+)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_TABLE_PATTERN = Pattern.compile("\\bUPDATE\\s+([`'\"]?)([^`'\"\\s,;]+)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRUNCATE_TABLE_PATTERN = Pattern.compile("\\bTRUNCATE\\s+TABLE\\s+([`'\"]?)([^`'\"\\s,;]+)\\1|\\bTRUNCATE\\s+([`'\"]?)([^`'\"\\s,;]+)\\3", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_TABLE_PATTERN = Pattern.compile("\\bDROP\\s+TABLE\\s+([`'\"]?)([^`'\"\\s,;]+)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_INDEX_PATTERN = Pattern.compile("\\bDROP\\s+INDEX\\s+([`'\"]?)([^`'\"\\s,;]+)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile("\\bCREATE\\s+TABLE\\s+([`'\"]?)([^`'\"\\s,;]+)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_INDEX_PATTERN = Pattern.compile("\\bCREATE\\s+INDEX\\s+([`'\"]?)([^`'\"\\s,;]+)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_TABLE_PATTERN = Pattern.compile("\\bALTER\\s+TABLE\\s+([`'\"]?)([^`'\"\\s,;]+)\\1", Pattern.CASE_INSENSITIVE);

    private SqlRiskConfirmDialogUtil() {
    }

    /**
     * 检查SQL是否为高危操作。
     *
     * @param sql SQL语句
     * @return 如果是高危操作返回风险描述，否则返回null
     */
    public static String checkRisk(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        String cleaned = cleanSql(sql);

        if (CREATE_TABLE_PATTERN.matcher(cleaned).find()) {
            return "数据库结构变更";
        }

        if (CREATE_INDEX_PATTERN.matcher(cleaned).find()) {
            return "数据库结构变更";
        }

        if (ALTER_TABLE_PATTERN.matcher(cleaned).find()) {
            return "数据库结构变更";
        }

        if (DROP_TABLE_PATTERN.matcher(cleaned).find()) {
            return "数据库结构变更";
        }

        if (DROP_INDEX_PATTERN.matcher(cleaned).find()) {
            return "数据库结构变更";
        }

        if (TRUNCATE_TABLE_PATTERN.matcher(cleaned).find()) {
            return "数据库结构变更";
        }

        if (INSERT_PATTERN.matcher(cleaned).find()) {
            return "写入数据";
        }

        if (DELETE_PATTERN.matcher(cleaned).find()) {
            return WHERE_PATTERN.matcher(cleaned).find() ? "删除数据" : "删除数据（无WHERE条件）";
        }

        if (UPDATE_PATTERN.matcher(cleaned).find()) {
            return WHERE_PATTERN.matcher(cleaned).find() ? "更新数据" : "全表更新（无WHERE条件）";
        }

        if (hasMultipleStatements(cleaned)) {
            return "批量执行SQL";
        }

        return null;
    }

    /**
     * 清洗SQL语句：去除注释、多余空格、换行。
     */
    private static String cleanSql(String sql) {
        String cleaned = sql;
        cleaned = Pattern.compile("--.*$", Pattern.MULTILINE).matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("/\\*[\\s\\S]*?\\*/", "");
        cleaned = cleaned.replaceAll("\\s+", " ");
        return cleaned.trim();
    }

    /**
     * 检查是否包含多条SQL语句。
     */
    private static boolean hasMultipleStatements(String sql) {
        String withoutTrailing = sql;
        if (withoutTrailing.endsWith(";")) {
            withoutTrailing = withoutTrailing.substring(0, withoutTrailing.length() - 1);
        }
        return withoutTrailing.contains(";");
    }

    /**
     * 提取SQL中涉及的数据表名。
     *
     * @param sql SQL语句
     * @return 数据表名列表，用逗号分隔
     */
    public static String extractTables(String sql) {
        if (sql == null || sql.isBlank()) {
            return "未知";
        }
        String cleaned = cleanSql(sql);
        List<String> tables = new ArrayList<>();

        Matcher createTableMatcher = CREATE_TABLE_PATTERN.matcher(cleaned);
        while (createTableMatcher.find()) {
            String table = createTableMatcher.group(2);
            if (table != null && !table.isEmpty() && !tables.contains(table)) {
                tables.add(table);
            }
        }

        Matcher createIndexMatcher = CREATE_INDEX_PATTERN.matcher(cleaned);
        while (createIndexMatcher.find()) {
            String table = createIndexMatcher.group(2);
            if (table != null && !table.isEmpty() && !tables.contains(table)) {
                tables.add(table);
            }
        }

        Matcher alterTableMatcher = ALTER_TABLE_PATTERN.matcher(cleaned);
        while (alterTableMatcher.find()) {
            String table = alterTableMatcher.group(2);
            if (table != null && !table.isEmpty() && !tables.contains(table)) {
                tables.add(table);
            }
        }

        Matcher truncateMatcher = TRUNCATE_TABLE_PATTERN.matcher(cleaned);
        while (truncateMatcher.find()) {
            String table = truncateMatcher.group(2);
            if (table == null || table.isEmpty()) {
                table = truncateMatcher.group(4);
            }
            if (table != null && !table.isEmpty() && !tables.contains(table)) {
                tables.add(table);
            }
        }

        Matcher dropTableMatcher = DROP_TABLE_PATTERN.matcher(cleaned);
        while (dropTableMatcher.find()) {
            String table = dropTableMatcher.group(2);
            if (table != null && !table.isEmpty() && !tables.contains(table)) {
                tables.add(table);
            }
        }

        Matcher dropIndexMatcher = DROP_INDEX_PATTERN.matcher(cleaned);
        while (dropIndexMatcher.find()) {
            String table = dropIndexMatcher.group(2);
            if (table != null && !table.isEmpty() && !tables.contains(table)) {
                tables.add(table);
            }
        }

        Matcher updateMatcher = UPDATE_TABLE_PATTERN.matcher(cleaned);
        while (updateMatcher.find()) {
            String table = updateMatcher.group(2);
            if (table != null && !table.isEmpty() && !tables.contains(table)) {
                tables.add(table);
            }
        }

        Matcher fromMatcher = TABLE_NAME_PATTERN.matcher(cleaned);
        while (fromMatcher.find()) {
            String table = fromMatcher.group(2);
            if (table != null && !table.isEmpty() && !tables.contains(table)) {
                tables.add(table);
            }
        }

        if (tables.isEmpty()) {
            return "未知";
        }
        return String.join(", ", tables);
    }

    /**
     * 显示高危SQL确认弹窗。
     *
     * <p>弹窗为模态阻断，用户必须选择「取消」或「确认执行」后才能继续操作。
     *
     * @param sql       待执行的SQL语句
     * @param onConfirm 用户点击「确认执行」后的回调
     */
    public static void showRiskConfirmDialog(String sql, Runnable onConfirm) {
        showRiskConfirmDialog(sql, onConfirm, null);
    }

    /**
     * 显示高危SQL确认弹窗（带指定父窗口）。
     *
     * @param sql       待执行的SQL语句
     * @param onConfirm 用户点击「确认执行」后的回调
     * @param owner     父窗口，用于模态关联；可为null
     */
    public static void showRiskConfirmDialog(String sql, Runnable onConfirm, Window owner) {
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(SqlRiskConfirmDialogUtil.class.getResource("/fxml/sql-risk-confirm-dialog.fxml"));
                Parent root = loader.load();

                SqlRiskConfirmDialogController controller = loader.getController();
                String riskType = checkRisk(sql);
                if (riskType == null) {
                    riskType = "需要确认的高风险操作";
                }
                String affectedTables = extractTables(sql);
                controller.setContent(sql, riskType, affectedTables, onConfirm);

                Stage dialog = new Stage();
                dialog.setTitle("高危SQL操作确认");
                dialog.setScene(new Scene(root));
                dialog.initModality(Modality.APPLICATION_MODAL);
                if (owner != null) {
                    dialog.initOwner(owner);
                }
                dialog.setResizable(true);
                dialog.showAndWait();
            } catch (IOException e) {
                log.error("Failed to load risk confirm dialog FXML", e);
            } catch (Exception e) {
                log.error("Unexpected error showing risk confirm dialog", e);
            }
        });
    }
}
