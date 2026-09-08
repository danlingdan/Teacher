package com.sqlteacher.infrastructure.database;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLTimeoutException;
import java.util.Set;

final class JdbcFailureClassifier {
    private static final Set<Integer> AUTHENTICATION_CODES = Set.of(1045);
    private static final Set<Integer> PERMISSION_CODES = Set.of(1044, 1142, 1143, 1227, 1370, 1419);
    private static final java.util.regex.Pattern CREDENTIAL_FRAGMENT = java.util.regex.Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key)\\s*[=:]\\s*(\\S+)");
    private static final java.util.regex.Pattern PATH_FRAGMENT = java.util.regex.Pattern.compile(
            "(?:[A-Za-z]:\\\\[^\\s（）()]+|/[\\w.@-]+(?:/[\\w.@-]+)+)");

    private JdbcFailureClassifier() {
    }

    static JdbcFailure classify(Throwable error) {
        Throwable current = error;
        boolean connectionFailure = false;
        for (int depth = 0; current != null && depth < 12; depth++, current = current.getCause()) {
            if (current instanceof SQLInvalidAuthorizationSpecException) {
                return JdbcFailure.AUTHENTICATION;
            }
            if (current instanceof SQLTimeoutException || current instanceof SocketTimeoutException) {
                return JdbcFailure.TIMEOUT;
            }
            if (current instanceof UnknownHostException || current instanceof ConnectException) {
                connectionFailure = true;
            }
            if (current instanceof SQLException sqlError) {
                String state = sqlError.getSQLState();
                int code = sqlError.getErrorCode();
                if (AUTHENTICATION_CODES.contains(code) || startsWith(state, "28")) {
                    return JdbcFailure.AUTHENTICATION;
                }
                if (PERMISSION_CODES.contains(code) || "42501".equals(state)) {
                    return JdbcFailure.PERMISSION;
                }
                if (startsWith(state, "HYT")) {
                    return JdbcFailure.TIMEOUT;
                }
                if (startsWith(state, "08")) {
                    connectionFailure = true;
                }
            }
        }
        return connectionFailure ? JdbcFailure.CONNECTION : JdbcFailure.SQL;
    }

    static String sqlState(Throwable error) {
        SQLException sqlError = findSqlException(error);
        return sqlError == null || sqlError.getSQLState() == null ? "" : sqlError.getSQLState();
    }

    static int vendorCode(Throwable error) {
        SQLException sqlError = findSqlException(error);
        return sqlError == null ? 0 : sqlError.getErrorCode();
    }

    /**
     * Builds a local-display detail suffix (SQLState, vendor code, root cause) for the desktop
     * UI, where the user owns the connection and needs the concrete failure reason. Log and
     * server-side paths keep using the classified message and structured fields only.
     */
    static String localDetail(Throwable error) {
        String sqlState = sqlState(error);
        int vendorCode = vendorCode(error);
        Throwable root = rootCause(error);
        String cause = "";
        if (root != null && root.getMessage() != null && !root.getMessage().isBlank()) {
            cause = root.getClass().getSimpleName() + ": "
                    + root.getMessage().replace('\n', ' ').replace('\r', ' ').strip();
            cause = CREDENTIAL_FRAGMENT.matcher(cause).replaceAll("$1=***");
            cause = PATH_FRAGMENT.matcher(cause).replaceAll("<path>");
            if (cause.length() > 200) {
                cause = cause.substring(0, 200) + "...";
            }
        }
        StringBuilder detail = new StringBuilder("（");
        if (!sqlState.isBlank()) {
            detail.append("SQLState=").append(sqlState);
        }
        if (vendorCode != 0) {
            if (detail.length() > 1) {
                detail.append("，");
            }
            detail.append("vendorCode=").append(vendorCode);
        }
        if (!cause.isBlank()) {
            if (detail.length() > 1) {
                detail.append("，");
            }
            detail.append(cause);
        }
        detail.append("）");
        return detail.length() == 2 ? "" : detail.toString();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && current.getCause() != null && depth < 12; depth++) {
            current = current.getCause();
        }
        return current;
    }

    private static SQLException findSqlException(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++, current = current.getCause()) {
            if (current instanceof SQLException sqlError) {
                return sqlError;
            }
        }
        return null;
    }

    private static boolean startsWith(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }

    enum JdbcFailure {
        AUTHENTICATION(
            "DATABASE_AUTHENTICATION_FAILED",
            "数据库身份验证失败，请检查用户名和临时密码。"
        ),
        PERMISSION(
            "DATABASE_PERMISSION_DENIED",
            "数据库账号权限不足，请联系管理员配置只读查询权限。"
        ),
        TIMEOUT(
            "DATABASE_CONNECTION_TIMEOUT",
            "数据库连接超时，请检查网络、地址和服务状态。"
        ),
        CONNECTION(
            "DATABASE_CONNECTION_FAILED",
            "无法连接数据库，请检查地址、端口和服务状态。"
        ),
        SQL(
            "SQL_EXECUTION_FAILED",
            "SQL 执行失败，请检查语法、表名和字段名后重试。"
        );

        private final String errorCode;
        private final String userMessage;

        JdbcFailure(String errorCode, String userMessage) {
            this.errorCode = errorCode;
            this.userMessage = userMessage;
        }

        String errorCode() {
            return errorCode;
        }

        String userMessage() {
            return userMessage;
        }
    }
}
