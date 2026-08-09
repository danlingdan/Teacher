package com.sqlteacher.application.databaselearning;

import java.util.List;

public interface DatabaseModelingService {
    ModelDraft draft(String requirement);

    record ModelDraft(String title, String explanation, List<TableDraft> tables, String ddl) {
        public ModelDraft { tables = List.copyOf(tables); }
    }

    record TableDraft(String name, String purpose, List<ColumnDraft> columns) {
        public TableDraft { columns = List.copyOf(columns); }
    }

    record ColumnDraft(String name, String type, boolean primaryKey, boolean nullable, String reference) { }
}
