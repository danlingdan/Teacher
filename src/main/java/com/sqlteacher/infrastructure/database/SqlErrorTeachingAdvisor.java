package com.sqlteacher.infrastructure.database;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v3.5.0 SFE-2: teaching interpretation for raw database errors. A pure deterministic
 * mapping (message pattern → 教学文案) beside the existing failure classifier; it never
 * changes the safety path and unknown messages pass through untouched. Copy states
 * "常见原因" rather than absolute causes so a pattern match on one engine cannot
 * mislabel a different failure.
 */
final class SqlErrorTeachingAdvisor {
    /** Stable marker; the frontend splits on it to render the note as its own block. */
    static final String MARKER = "\n教学解读：";

    private static final Pattern NEAR_SYNTAX = Pattern.compile("near\\s+\"([^\"]*)\"\\s*:\\s*syntax error", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONSTRAINT_COLUMNS = Pattern.compile(
        "constraint failed:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_SUCH_TABLE = Pattern.compile("no such table:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_SUCH_COLUMN = Pattern.compile("no such column:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_SUCH_FUNCTION = Pattern.compile("no such function:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);

    private SqlErrorTeachingAdvisor() {
    }

    /** Appends {@link #MARKER} plus the interpretation when one matches; otherwise returns the text unchanged. */
    static String append(String text, String rawErrorMessage) {
        String note = advise(rawErrorMessage);
        return note.isBlank() ? text : text + MARKER + note;
    }

    static String append(String text, Throwable error) {
        String note = advise(error);
        return note.isBlank() ? text : text + MARKER + note;
    }

    /** The teaching note previously embedded after {@link #MARKER}, or "" when absent. */
    static String embeddedNote(String text) {
        if (text == null) {
            return "";
        }
        int marker = text.indexOf(MARKER);
        return marker < 0 ? "" : text.substring(marker + MARKER.length()).trim();
    }

    static String advise(Throwable error) {
        if (error == null) {
            return "";
        }
        Throwable current = error;
        for (int depth = 0; current.getCause() != null && depth < 12; depth++) {
            current = current.getCause();
        }
        return advise(current.getMessage());
    }

    static String advise(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return "";
        }
        String message = rawMessage.trim();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("foreign key constraint")) {
            return "违反了外键约束（参照完整性）：写入的值在引用的表中不存在，或删除/修改的行仍被其他表引用。"
                + "常见原因：先插入父表（如 Student、Course）再插入子表（如 SC）；删除前先清理子表里的引用；"
                + "自引用表（如 Course 的先修课 Cpno）还要注意先修课程是否已存在。";
        }
        Matcher near = NEAR_SYNTAX.matcher(message);
        if (near.find()) {
            String token = near.group(1);
            return "SQL 语法错误，问题出在 near 后面的 “" + token + "” 附近。常见原因：缺少逗号、引号或括号，"
                + "关键字拼写错误，使用了中文标点（如中文引号、中文分号），或子句顺序不对（如 WHERE 写在 GROUP BY 之后）。";
        }
        if (lower.contains("not null constraint")) {
            String column = firstConstraintTarget(message);
            return "违反了非空约束：" + targetLabel(column, "对应列") + "不能取 NULL，请为它提供一个具体值。";
        }
        if (lower.contains("unique constraint")) {
            String columns = firstConstraintTarget(message);
            return "违反了唯一约束：" + targetLabel(columns, "相关列") + "的值已存在或重复了。"
                + "常见原因：主键值重复，或为唯一列（如学号、课程号）写入了已有值。";
        }
        if (lower.contains("check constraint")) {
            return "违反了 CHECK 约束：新写入的值不满足建表时规定的取值范围或条件，请核对该列允许的取值。";
        }
        Matcher noTable = NO_SUCH_TABLE.matcher(message);
        if (noTable.find()) {
            return "表 “" + noTable.group(1) + "” 在当前数据库中不存在。常见原因：表名拼写或大小写不对、"
                + "连接到了别的数据库、或建表语句还没执行。";
        }
        Matcher noColumn = NO_SUCH_COLUMN.matcher(message);
        if (noColumn.find()) {
            return "列 “" + noColumn.group(1) + "” 在对应表中不存在。常见原因：列名拼写不对、"
                + "多表查询时同名列没有用 表名.列名 限定、或给表起了别名后仍用原表名引用。";
        }
        Matcher noFunction = NO_SUCH_FUNCTION.matcher(message);
        if (noFunction.find()) {
            return "函数 “" + noFunction.group(1) + "” 不存在。常见原因：函数名拼写不对，"
                + "或使用了当前数据库不支持的函数（不同数据库的函数名可能不同）。";
        }
        if (lower.contains("unrecognized token")) {
            return "SQL 里出现了无法识别的符号。常见原因：字符串没有用英文单引号包起来、"
                + "混入了中文引号或全角空格等不可见字符。";
        }
        if (lower.contains("incomplete input")) {
            return "语句不完整：可能缺少结尾的分号或括号、引号没有闭合，语句在结束前就中断了。";
        }
        if (lower.contains("misuse of aggregate")) {
            return "聚合函数使用不当。常见原因：聚合结果（如 COUNT、SUM）写在了 WHERE 里（应改用 HAVING），"
                + "或 SELECT 里的非聚合列没有出现在 GROUP BY 中。";
        }
        return "";
    }

    /** “constraint failed: a.b, c.d” → targets after the colon; "" when the shape differs. */
    private static String firstConstraintTarget(String message) {
        Matcher matcher = CONSTRAINT_COLUMNS.matcher(message);
        if (!matcher.find()) {
            return "";
        }
        String[] parts = matcher.group(1).trim().split(",");
        List<String> targets = new ArrayList<>(parts.length);
        for (String part : parts) {
            String target = part.trim();
            if (!target.isEmpty()) {
                targets.add(target);
            }
        }
        return String.join("、", targets);
    }

    private static String targetLabel(String target, String fallback) {
        return target == null || target.isBlank() ? fallback : "“" + target + "”";
    }
}
