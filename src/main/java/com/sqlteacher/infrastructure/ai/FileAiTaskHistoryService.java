package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sqlteacher.application.ai.AiTaskHistoryEntry;
import com.sqlteacher.application.ai.AiTaskHistoryService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class FileAiTaskHistoryService implements AiTaskHistoryService {
    private static final int MAX_ENTRIES = 100;
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final List<AiTaskHistoryEntry> entries;

    public FileAiTaskHistoryService(Path file) {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        this.entries = load();
    }

    @Override public synchronized List<AiTaskHistoryEntry> recent() {
        return entries.stream().sorted(Comparator.comparing(AiTaskHistoryEntry::createdAt).reversed()).toList();
    }

    @Override public synchronized void record(AiTaskHistoryEntry entry) {
        entries.add(Objects.requireNonNull(entry));
        while (entries.size() > MAX_ENTRIES) {
            int index = 0;
            for (int i = 0; i < entries.size(); i++) if (!entries.get(i).favorite()) { index = i; break; }
            entries.remove(index);
        }
        save();
    }

    @Override public synchronized void favorite(String id, boolean favorite, String draftContent) {
        for (int i = 0; i < entries.size(); i++) {
            AiTaskHistoryEntry old = entries.get(i);
            if (!old.id().equals(id)) continue;
            String saved = favorite ? safeDraft(draftContent) : "";
            entries.set(i, new AiTaskHistoryEntry(old.id(), old.createdAt(), old.taskType(), old.model(),
                old.successful(), old.resultCode(), old.durationMillis(), old.promptVersion(), favorite, saved));
            save();
            return;
        }
    }

    @Override public synchronized int requestsToday() {
        LocalDate today = LocalDate.now();
        return (int) entries.stream().filter(entry -> LocalDate.ofInstant(entry.createdAt(), ZoneId.systemDefault()).equals(today)).count();
    }

    private List<AiTaskHistoryEntry> load() {
        if (Files.notExists(file)) return new ArrayList<>();
        try { return new ArrayList<>(mapper.readValue(file.toFile(), new TypeReference<List<AiTaskHistoryEntry>>() {})); }
        catch (Exception error) { return new ArrayList<>(); }
    }

    private void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), entries);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to persist AI task history", error);
        }
    }

    private static String safeDraft(String content) {
        if (content == null) return "";
        String value = content.strip();
        return value.length() <= 8_000 ? value : value.substring(0, 8_000);
    }
}
