package com.sqlteacher.application.databaselearning;

import com.sqlteacher.application.knowledge.SafeWebContentFetcher;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class DefaultWebDataLabService implements WebDataLabService {
    private static final int MAX_ROWS = 50;
    private static final int MAX_COLUMNS = 16;
    private static final int MAX_CELL_LENGTH = 256;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private final SafeWebContentFetcher fetcher;

    public DefaultWebDataLabService(SafeWebContentFetcher fetcher) {
        this.fetcher = Objects.requireNonNull(fetcher);
    }

    @Override
    public DataPreview preview(URI uri) {
        var content = fetcher.fetch(uri);
        List<List<String>> parsed = parseDelimited(content.text());
        if (parsed.isEmpty()) throw new IllegalArgumentException("页面中没有可预览的文本数据");
        int width = parsed.stream().mapToInt(List::size).max().orElse(1);
        width = Math.min(width, MAX_COLUMNS);
        List<String> columns = header(parsed.getFirst(), width);
        List<List<String>> rows = new ArrayList<>();
        for (int index = 1; index < parsed.size() && rows.size() < MAX_ROWS; index++) {
            rows.add(normalize(parsed.get(index), width));
        }
        if (rows.isEmpty()) rows.add(normalize(parsed.getFirst(), width));
        return new DataPreview(content.finalUri(), content.title(), columns, rows, content.contentHash());
    }

    @Override
    public String buildInsertDraft(String tableName, DataPreview preview) {
        Objects.requireNonNull(preview);
        if (tableName == null || !IDENTIFIER.matcher(tableName.strip()).matches()) {
            throw new IllegalArgumentException("目标表名只能包含英文字母、数字和下划线，且不能以数字开头");
        }
        if (preview.rows().isEmpty()) throw new IllegalArgumentException("没有可生成的预览行");
        List<String> columns = preview.columns().stream().map(DefaultWebDataLabService::identifier).toList();
        List<String> values = preview.rows().stream().limit(25).map(row -> "(" + row.stream()
            .limit(columns.size()).map(DefaultWebDataLabService::literal).reduce((a, b) -> a + ", " + b).orElse("") + ")").toList();
        return "-- 来源：" + preview.source() + "\n-- 仅为待审查草稿，不会自动执行\nINSERT INTO "
            + identifier(tableName.strip()) + " (" + String.join(", ", columns) + ") VALUES\n"
            + String.join(",\n", values) + ";";
    }

    static List<List<String>> parseDelimited(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isBlank()) return List.of();
        String[] lines = normalized.split("\n");
        if (lines.length == 1) lines = normalized.split("\\s{2,}");
        List<List<String>> result = new ArrayList<>();
        for (String line : lines) {
            if (result.size() > MAX_ROWS) break;
            List<String> cells = parseCsvLine(line);
            if (!cells.isEmpty()) result.add(cells.stream().limit(MAX_COLUMNS).toList());
        }
        return result;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = !quoted;
            } else if (current == ',' && !quoted) { cells.add(clean(cell.toString())); cell.setLength(0); }
            else cell.append(current);
        }
        cells.add(clean(cell.toString()));
        return cells;
    }

    private static List<String> header(List<String> first, int width) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            String candidate = i < first.size() ? first.get(i).strip() : "";
            result.add(IDENTIFIER.matcher(candidate).matches() ? candidate : "column_" + (i + 1));
        }
        return List.copyOf(result);
    }

    private static List<String> normalize(List<String> row, int width) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < width; i++) result.add(i < row.size() ? clean(row.get(i)) : "");
        return List.copyOf(result);
    }

    private static String clean(String value) {
        String cleaned = value == null ? "" : value.strip();
        return cleaned.length() <= MAX_CELL_LENGTH ? cleaned : cleaned.substring(0, MAX_CELL_LENGTH);
    }

    private static String identifier(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    private static String literal(String value) { return "'" + clean(value).replace("'", "''") + "'"; }
}
