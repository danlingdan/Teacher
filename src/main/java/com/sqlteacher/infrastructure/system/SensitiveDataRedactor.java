package com.sqlteacher.infrastructure.system;

import java.util.regex.Pattern;

public final class SensitiveDataRedactor {
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]{12,}");
    private static final Pattern SECRET = Pattern.compile("(?i)(password|token|api[-_ ]?key|secret|authorization)\\s*[:=]\\s*[^\\s,;]+", Pattern.UNICODE_CASE);
    private static final Pattern USER_PATH = Pattern.compile("(?i)(?:[A-Z]:\\\\Users\\\\)[^\\\\/]+", Pattern.UNICODE_CASE);
    private static final Pattern SQL_LITERAL = Pattern.compile("(?is)\\b(select|insert|update|delete|drop|alter|create)\\b.{20,}");
    private SensitiveDataRedactor() { }

    public static String redact(String value) {
        if (value == null) return "";
        String result = BEARER.matcher(value).replaceAll("Bearer [REDACTED]");
        result = SECRET.matcher(result).replaceAll("$1=[REDACTED]");
        result = USER_PATH.matcher(result).replaceAll("C:\\Users\\[USER]");
        result = SQL_LITERAL.matcher(result).replaceAll("[SQL REDACTED]");
        result = result.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "?");
        return result.length() > 2000 ? result.substring(0, 2000) : result;
    }
}
