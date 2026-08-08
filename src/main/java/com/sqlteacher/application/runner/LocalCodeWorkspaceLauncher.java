package com.sqlteacher.application.runner;

import com.sqlteacher.domain.activity.CodeLanguage;

public interface LocalCodeWorkspaceLauncher {
    LocalCodeWorkspace open(CodeLanguage language, String sourceCode);
}
