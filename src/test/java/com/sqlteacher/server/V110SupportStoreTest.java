package com.sqlteacher.server;

import com.sqlteacher.application.support.ProblemReportReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;
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

    @Test void withdrawsOnlyOwnUnprocessedReportsAndIsIdempotent() throws Exception {
        V110SupportStore store = new V110SupportStore(directory.resolve("cloud.db"));
        ProblemReportReceipt receipt = store.submit(validBody(), null, "127.0.0.1");
        assertThrows(SecurityException.class, () -> store.withdraw(receipt.reportId(), "wrong-token"));
        store.withdraw(receipt.reportId(), receipt.queryToken());
        assertEquals(ProblemReportReceipt.Status.WITHDRAWN, store.status(receipt.reportId(), receipt.queryToken()).status());
        store.withdraw(receipt.reportId(), receipt.queryToken());
    }

    @Test void exportsOnlyOwnReportMetadata() throws Exception {
        V110SupportStore store = new V110SupportStore(directory.resolve("cloud.db"));
        ProblemReportReceipt receipt = store.submit(validBody(), "user-1", "127.0.0.1");
        assertThrows(SecurityException.class, () -> store.export(receipt.reportId(), "wrong-token"));
        var export = store.export(receipt.reportId(), receipt.queryToken());
        assertEquals(receipt.reportId(), export.reportId());
        assertEquals("BUG", export.type());
        assertEquals("Update failed", export.summary());
        assertEquals(1, export.history().size());
        assertEquals("RECEIVED", export.history().getFirst().status());
    }

    @Test void rejectsInvalidOrOversizedScreenshotsButStoresValidOnes() throws Exception {
        V110SupportStore store = new V110SupportStore(directory.resolve("cloud.db"));
        Map<String, Object> oversized = validBody(); oversized.put("idempotencyKey", "oversized");
        oversized.put("screenshot", Map.of("filename", "shot.png", "mimeType", "image/png", "data", Base64.getEncoder().encodeToString(new byte[2 * 1024 * 1024 + 1])));
        assertThrows(IllegalArgumentException.class, () -> store.submit(oversized, null, "127.0.0.1"));

        Map<String, Object> badMime = validBody(); badMime.put("idempotencyKey", "badmime");
        badMime.put("screenshot", Map.of("filename", "shot.bmp", "mimeType", "image/bmp", "data", Base64.getEncoder().encodeToString(new byte[8])));
        assertThrows(IllegalArgumentException.class, () -> store.submit(badMime, null, "127.0.0.1"));

        Map<String, Object> valid = validBody(); valid.put("idempotencyKey", "withshot");
        valid.put("screenshot", Map.of("filename", "shot.png", "mimeType", "image/png", "data", Base64.getEncoder().encodeToString(new byte[64])));
        assertDoesNotThrow(() -> store.submit(valid, null, "127.0.0.1"));
    }

    private static Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", "draft-1"); body.put("installId", "install-1"); body.put("type", "BUG");
        body.put("severity", "PARTIAL_FAILURE"); body.put("summary", "Update failed"); body.put("description", "Download did not finish");
        body.put("application", Map.of("version", "1.10.0")); body.put("diagnostics", Map.of("environment", Map.of("os", "Windows")));
        return body;
    }
}
