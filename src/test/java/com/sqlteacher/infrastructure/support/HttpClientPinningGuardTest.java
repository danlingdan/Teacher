package com.sqlteacher.infrastructure.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the HTTP/1.1 pinning policy: production code must build outbound
 * {@link java.net.http.HttpClient} instances only through {@link HttpClients}, which pins
 * HTTP/1.1 to avoid the JDK h2c upgrade deadlock on plaintext loopback POSTs.
 */
class HttpClientPinningGuardTest {
    private static final Path MAIN_ROOT = Path.of("src", "main", "java");
    private static final String BUILDER_MARKER = "HttpClient.newBuilder";
    private static final String HTTP1_PIN = "HttpClient.Version.HTTP_1_1";

    /**
     * The factory is the only intended builder call site. The two server clients are owned
     * by the cloud-server module and build directly; they are tolerated only while they
     * keep their explicit HTTP/1.1 pin (asserted below).
     */
    private static final Set<String> ALLOWED_DIRECT_BUILDER_FILES = Set.of(
        "com/sqlteacher/infrastructure/support/HttpClients.java",
        "com/sqlteacher/server/OllamaCloudKnowledgeEmbeddingClient.java",
        "com/sqlteacher/server/QdrantVectorClient.java"
    );

    @Test
    void productionSourcesBuildHttpClientsOnlyThroughTheSharedFactory() throws IOException {
        assertTrue(Files.isDirectory(MAIN_ROOT),
            "src/main/java must exist relative to the working directory; the scan must not silently pass");
        List<String> violations = new ArrayList<>();
        try (var sources = Files.walk(MAIN_ROOT)) {
            for (Path source : sources.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                if (!Files.readString(source).contains(BUILDER_MARKER)) {
                    continue;
                }
                String relative = MAIN_ROOT.relativize(source).toString().replace('\\', '/');
                if (!ALLOWED_DIRECT_BUILDER_FILES.contains(relative)) {
                    violations.add(relative + " builds an HttpClient directly; use com.sqlteacher."
                        + "infrastructure.support.HttpClients so the client is pinned to HTTP/1.1");
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "Direct HttpClient builder call sites found:" + System.lineSeparator() + String.join(System.lineSeparator(), violations));
    }

    @Test
    void whitelistEntriesExistAndKeepTheirExplicitHttp11Pin() throws IOException {
        assertFalse(ALLOWED_DIRECT_BUILDER_FILES.isEmpty(), "the whitelist must not be silently emptied");
        for (String relative : ALLOWED_DIRECT_BUILDER_FILES) {
            Path source = MAIN_ROOT.resolve(relative);
            assertTrue(Files.isRegularFile(source),
                "Whitelisted file " + relative + " no longer exists; remove it from the whitelist");
            assertTrue(Files.readString(source).contains(HTTP1_PIN),
                "Whitelisted file " + relative + " must keep an explicit " + HTTP1_PIN + " pin");
        }
    }
}
