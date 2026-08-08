package com.sqlteacher.application.runner;

import com.sqlteacher.domain.activity.CodeLanguage;

import java.util.List;

public interface CodeRunner {
    List<RunnerCapability> capabilities();

    default RunnerCapability capability(CodeLanguage language) {
        return capabilities().stream().filter(item -> item.language() == language).findFirst()
            .orElse(new RunnerCapability(language, false, "RUNNER_CAPABILITY_UNKNOWN"));
    }

    CodeRunResult run(CodeRunRequest request, RunnerCancellation cancellation);
}
