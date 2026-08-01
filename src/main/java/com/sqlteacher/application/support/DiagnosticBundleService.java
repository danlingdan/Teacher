package com.sqlteacher.application.support;

import java.nio.file.Path;
import java.util.Map;

public interface DiagnosticBundleService {
    Map<String, Object> preview(DiagnosticSelection selection);
    Path export(DiagnosticSelection selection, Path destinationDirectory);
    void recordFailure(Throwable error, String source);
    boolean previousRunCrashed();
    void markUiReady();
    void markCleanShutdown();
}
