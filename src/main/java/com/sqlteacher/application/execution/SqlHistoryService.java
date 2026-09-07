package com.sqlteacher.application.execution;

import java.util.List;

/** Local, private history of executed SQL statements for the data workbench. */
public interface SqlHistoryService {
    /** Keeps at most this many newest entries per application database. */
    int MAX_ENTRIES = 200;

    void record(SqlHistoryEntry entry);

    List<SqlHistoryEntry> list(int limit);

    void clear();
}
