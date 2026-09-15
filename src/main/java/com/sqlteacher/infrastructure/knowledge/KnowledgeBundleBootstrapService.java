package com.sqlteacher.infrastructure.knowledge;

import com.sqlteacher.application.knowledge.KnowledgeBundleService;
import com.sqlteacher.application.knowledge.KnowledgeBundleSource;
import com.sqlteacher.application.knowledge.KnowledgeBundleState;
import com.sqlteacher.application.knowledge.KnowledgeBundleSummary;
import com.sqlteacher.application.knowledge.KnowledgeBundleVersions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * First-run import of the installer-bundled official knowledge base (v3.4.3 OKB-5).
 *
 * <p>Runs once in the background shortly after startup: if a bundle zip ships in the resource
 * {@code knowledge} directory and no equal-or-newer version of that bundle is installed yet, it
 * is imported as {@link KnowledgeBundleSource#BUILTIN}. Already-imported bundles (from any
 * source) are never downgraded. Missing directories, unreadable zips, offline embedding, or any
 * other failure degrade silently so the core local flow is never blocked.
 */
public final class KnowledgeBundleBootstrapService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBundleBootstrapService.class);
    private static final long INITIAL_DELAY_SECONDS = 20;

    private final KnowledgeBundleService bundleService;
    private final Path bundleDirectory;
    private ScheduledExecutorService executor;

    public KnowledgeBundleBootstrapService(KnowledgeBundleService bundleService, Path bundleDirectory) {
        this.bundleService = bundleService;
        this.bundleDirectory = bundleDirectory;
    }

    /** Starts the daemon scheduler; safe to call once from the wiring. */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "knowledge-bundle-bootstrap");
            thread.setDaemon(true);
            return thread;
        });
        executor.schedule(this::tick, INITIAL_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    void tick() {
        try {
            importBuiltinBundles();
        } catch (RuntimeException error) {
            log.info("Bundled knowledge import skipped: {}", error.getClass().getSimpleName());
        }
    }

    /** Scans the resource directory and imports any not-yet-installed bundle as builtin. Returns the imported count. */
    int importBuiltinBundles() {
        if (bundleDirectory == null || !Files.isDirectory(bundleDirectory)) {
            return 0;
        }
        List<Path> candidates;
        try (Stream<Path> stream = Files.list(bundleDirectory)) {
            candidates = stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".zip"))
                .sorted()
                .toList();
        } catch (IOException error) {
            log.info("Bundled knowledge directory is unreadable: {}", error.toString());
            return 0;
        }
        int imported = 0;
        for (Path zip : candidates) {
            try {
                KnowledgeBundleSummary summary = bundleService.inspectBundle(zip);
                Optional<KnowledgeBundleState> state = bundleService.findBundleState(summary.bundleId());
                boolean shouldImport = state.isEmpty()
                    || KnowledgeBundleVersions.isNewer(summary.version(), state.get().version());
                if (!shouldImport) {
                    continue;
                }
                bundleService.importBundle(zip, KnowledgeBundleSource.BUILTIN);
                imported++;
                log.info("Imported bundled knowledge base {} v{} ({} documents)",
                    summary.bundleId(), summary.version(), summary.documentCount());
            } catch (RuntimeException error) {
                // Fail-safe: one bad bundle must not block the others or the app.
                log.info("Skipped bundled knowledge zip {}: {}", zip.getFileName(), error.toString());
            }
        }
        return imported;
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
