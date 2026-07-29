package com.sqlteacher.application.ai;

public interface AiTaskService {
    AiTaskResult execute(AiTaskRequest request);
    default String preferredModel() { return ""; }
}
