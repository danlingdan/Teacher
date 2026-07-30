package com.sqlteacher.application.ai;

import java.util.List;

public interface AiContextPolicy {
    AiPreparedContext prepare(AiTaskType taskType, List<AiContextItem> requested);
}
