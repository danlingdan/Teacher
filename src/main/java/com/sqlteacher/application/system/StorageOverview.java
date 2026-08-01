package com.sqlteacher.application.system;

import java.util.Map;

public record StorageOverview(Map<String, Long> categoryBytes, long usableBytes) {
    public StorageOverview { categoryBytes = Map.copyOf(categoryBytes); }
}
