package com.sqlteacher.application.system;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Search model for the desktop command palette. Pure and UI-free so navigation
 * and safety classification can be unit-tested without JavaFX.
 */
public final class CommandPaletteModel {
    private final List<Command> commands = new ArrayList<>();

    /** A palette entry. {@code target} is the page/action identifier the desktop layer navigates to. */
    public record Command(String id, String label, String keyword, boolean destructive, String target) {
        public Command {
            id = id == null ? "" : id;
            label = label == null ? "" : label;
            keyword = keyword == null ? "" : keyword;
            target = target == null ? "" : target;
        }
    }

    public void register(String id, String label, String keyword, boolean destructive, String target) {
        commands.add(new Command(id, label, keyword, destructive, target));
    }

    /**
     * Filters by substring on label and keyword (both Chinese and English aliases live
     * in {@code label}/{@code keyword}); destructive commands are never hidden, but the
     * caller must still route them through the original confirmation flow.
     */
    public List<Command> search(String query, int limit) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<Command> matches = new ArrayList<>();
        for (Command command : commands) {
            if (q.isEmpty() || command.label().toLowerCase(Locale.ROOT).contains(q)
                || command.keyword().toLowerCase(Locale.ROOT).contains(q)) {
                matches.add(command);
            }
        }
        matches.sort(Comparator.comparing(Command::label));
        return matches.size() <= limit ? List.copyOf(matches) : List.copyOf(matches.subList(0, limit));
    }

    public List<Command> all() { return List.copyOf(commands); }
}
