package com.sqlteacher.infrastructure.execution;

import java.util.List;
import java.util.Map;

/**
 * Renders cached SQL result rows as CSV text (RFC 4180 quoting, UTF-8 BOM for Excel).
 * Cell values that spreadsheets may interpret as formulas (`=`, `+`, `-`, `@`, tab) are
 * neutralized with a leading apostrophe so a hostile cell cannot execute as a formula
 * when the export is opened (v3.3 W5.3).
 */
public final class SqlCsvExporter {
    private static final String BOM = "\uFEFF";

    private SqlCsvExporter() {
    }

    public static String toCsv(List<String> columns, List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder(BOM);
        out.append(String.join(",", columns.stream().map(SqlCsvExporter::escape).toList())).append("\r\n");
        for (Map<String, Object> row : rows) {
            out.append(String.join(",", columns.stream()
                .map(column -> escape(row.get(column)))
                .toList())).append("\r\n");
        }
        return out.toString();
    }

    private static String escape(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (startsWithFormulaTrigger(text)) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private static boolean startsWithFormulaTrigger(String text) {
        if (text.isEmpty()) {
            return false;
        }
        return switch (text.charAt(0)) {
            case '=', '+', '-', '@', '\t' -> true;
            default -> false;
        };
    }
}
