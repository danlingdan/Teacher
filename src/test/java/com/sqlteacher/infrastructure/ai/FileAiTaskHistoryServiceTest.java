package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.AiTaskHistoryEntry;
import com.sqlteacher.application.ai.AiTaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused coverage for the bounded history eviction semantics. */
class FileAiTaskHistoryServiceTest {
    private static final int MAX_ENTRIES = 100;

    @TempDir Path tempDirectory;

    @Test void evictsOldestNonFavoriteEntryWhenBoundIsExceeded() {
        FileAiTaskHistoryService service = new FileAiTaskHistoryService(tempDirectory.resolve("history.json"));
        for (int i = 0; i < MAX_ENTRIES; i++) service.record(entry(i, false));
        service.record(entry(MAX_ENTRIES, false));

        List<AiTaskHistoryEntry> recent = service.recent();

        assertEquals(MAX_ENTRIES, recent.size());
        assertTrue(recent.stream().noneMatch(entry -> entry.model().equals("model-0")),
            "the oldest non-favorite entry must be evicted first");
        assertTrue(recent.stream().anyMatch(entry -> entry.model().equals("model-" + MAX_ENTRIES)));
    }

    @Test void evictsOldestFavoriteWhenEveryEntryIsAFavorite() {
        FileAiTaskHistoryService service = new FileAiTaskHistoryService(tempDirectory.resolve("history.json"));
        for (int i = 0; i < MAX_ENTRIES; i++) service.record(entry(i, true));
        service.record(entry(MAX_ENTRIES, true));

        List<AiTaskHistoryEntry> recent = service.recent();

        assertEquals(MAX_ENTRIES, recent.size());
        assertTrue(recent.stream().noneMatch(entry -> entry.model().equals("model-0")),
            "when everything is favorited, the oldest favorite is deliberately evicted to keep the bound");
        assertTrue(recent.stream().anyMatch(entry -> entry.model().equals("model-" + MAX_ENTRIES)));
    }

    private static AiTaskHistoryEntry entry(int sequence, boolean favorite) {
        return new AiTaskHistoryEntry(UUID.randomUUID().toString(),
            Instant.now().plusSeconds(sequence), AiTaskType.NL2SQL, "model-" + sequence,
            true, "SUCCESS", 10, "test-v1", favorite, "");
    }
}
