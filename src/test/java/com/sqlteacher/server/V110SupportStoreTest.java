package com.sqlteacher.server;

import com.sqlteacher.application.support.ProblemReportReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class V110SupportStoreTest {
    @TempDir Path directory;

    @Test void isIdempotentAndUsesRotatingUnenumerableQueryCredentials() throws Exception {
        V110SupportStore store = new V110SupportStore(directory.resolve("cloud.db"));
        Map<String, Object> body = validBody();
        ProblemReportReceipt first = store.submit(body, null, "127.0.0.1");
        ProblemReportReceipt duplicate = store.submit(body, null, "127.0.0.1");
        assertEquals(first.reportId(), duplicate.reportId());
        assertFalse(duplicate.queryToken().isBlank());
        assertEquals(ProblemReportReceipt.Status.RECEIVED, store.status(duplicate.reportId(), duplicate.queryToken()).status());
        assertThrows(SecurityException.class, () -> store.status(first.reportId(), first.queryToken()));
    }

    @Test void rejectsForbiddenDiagnosticKeysButAllowsProductClassNames() throws Exception {
        V110SupportStore store = new V110SupportStore(directory.resolve("cloud.db"));
        Map<String, Object> allowed = validBody(); allowed.put("diagnostics", Map.of("frames", java.util.List.of("com.sqlteacher.SqlTeacherFxApp.start")));
        assertDoesNotThrow(() -> store.submit(allowed, "user-1", "127.0.0.1"));
        Map<String, Object> forbidden = validBody(); forbidden.put("idempotencyKey", "another"); forbidden.put("diagnostics", Map.of("accessToken", "secret"));
        assertThrows(IllegalArgumentException.class, () -> store.submit(forbidden, "user-1", "127.0.0.1"));
    }

    private static Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", "draft-1"); body.put("installId", "install-1"); body.put("type", "BUG");
        body.put("severity", "PARTIAL_FAILURE"); body.put("summary", "Update failed"); body.put("description", "Download did not finish");
        body.put("application", Map.of("version", "1.10.0")); body.put("diagnostics", Map.of("environment", Map.of("os", "Windows")));
        return body;
    }
}
