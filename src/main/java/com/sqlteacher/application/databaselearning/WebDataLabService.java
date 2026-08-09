package com.sqlteacher.application.databaselearning;

import java.net.URI;
import java.util.List;

public interface WebDataLabService {
    DataPreview preview(URI uri);
    String buildInsertDraft(String tableName, DataPreview preview);

    record DataPreview(URI source, String title, List<String> columns, List<List<String>> rows, String contentHash) {
        public DataPreview {
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
        }
    }
}
