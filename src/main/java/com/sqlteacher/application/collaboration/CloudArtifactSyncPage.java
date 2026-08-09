package com.sqlteacher.application.collaboration;

import java.util.List;

public record CloudArtifactSyncPage(List<CloudArtifactSyncItem> items, long cursor) {
    public CloudArtifactSyncPage {
        items = items == null ? List.of() : List.copyOf(items);
        if (cursor < 0) throw new IllegalArgumentException("cursor must not be negative");
    }
}
