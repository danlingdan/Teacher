package com.sqlteacher.infrastructure.execution;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlCsvExporterTest {
    @Test
    void shouldEscapeQuotesCommasAndLineBreaks() {
        String csv = SqlCsvExporter.toCsv(
            List.of("name", "note"),
            List.of(
                Map.of("name", "张三", "note", "常驻, 北京"),
                Map.of("name", "李\"四\"", "note", "两行\n文本")
            )
        );

        assertEquals("\uFEFFname,note\r\n张三,\"常驻, 北京\"\r\n\"李\"\"四\"\"\",\"两行\n文本\"\r\n", csv);
    }

    @Test
    void shouldRenderNullValuesAsEmptyCells() {
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("a", null);
        String csv = SqlCsvExporter.toCsv(List.of("a", "b"), List.of(row));

        assertEquals("\uFEFFa,b\r\n,\r\n", csv);
    }
}
