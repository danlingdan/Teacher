package com.sqlteacher.application.collaboration;

import java.util.List;

public record AdminAuditPage(List<AdminAuditEntry> entries, int page, int pageSize, int totalRows) {
    public AdminAuditPage {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
