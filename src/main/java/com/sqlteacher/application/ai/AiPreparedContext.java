package com.sqlteacher.application.ai;

import java.util.List;

public record AiPreparedContext(List<AiContextItem> items, AiContextPreview preview) {
    public AiPreparedContext {
        items = List.copyOf(items);
    }
}
