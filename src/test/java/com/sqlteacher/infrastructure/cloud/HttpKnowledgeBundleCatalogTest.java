package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.CloudApiRequestException;
import com.sqlteacher.application.knowledge.CloudKnowledgeBundleManifest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpKnowledgeBundleCatalogTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI start(byte[] manifestBody, int manifestStatus, byte[] bundleBody, int bundleStatus) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/app/knowledge-bundle-manifest", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(manifestStatus, manifestBody.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(manifestBody);
            }
        });
        server.createContext("/api/v1/app/knowledge-bundle", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(bundleStatus, bundleBody.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bundleBody);
            }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void parsesManifestWhenPublished() throws Exception {
        byte[] body = "{\"bundleId\":\"official-db-concepts\",\"version\":\"1.2.0\",\"title\":\"数据库系统概念\","
            .concat("\"sizeBytes\":4,\"sha256\":\"abcd\"}").getBytes(StandardCharsets.UTF_8);
        URI base = start(body, 200, new byte[0], 404);
        var catalog = new HttpKnowledgeBundleCatalog(base);

        Optional<CloudKnowledgeBundleManifest> manifest = catalog.fetchManifest();

        assertTrue(manifest.isPresent());
        assertEquals("official-db-concepts", manifest.get().bundleId());
        assertEquals("1.2.0", manifest.get().version());
        assertEquals(4, manifest.get().sizeBytes());
        assertEquals("abcd", manifest.get().sha256());
    }

    @Test
    void returnsEmptyWhenCloudPublishesNoBundle() throws Exception {
        byte[] body = "{\"code\":\"KNOWLEDGE_BUNDLE_UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8);
        URI base = start(body, 404, new byte[0], 404);
        var catalog = new HttpKnowledgeBundleCatalog(base);

        assertTrue(catalog.fetchManifest().isEmpty());
    }

    @Test
    void downloadsAndVerifiesChecksum() throws Exception {
        byte[] zip = "zip-bytes".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip));
        byte[] body = ("{\"bundleId\":\"b\",\"version\":\"1.0.0\",\"title\":\"t\",\"sizeBytes\":" + zip.length
            + ",\"sha256\":\"" + sha + "\"}").getBytes(StandardCharsets.UTF_8);
        URI base = start(body, 200, zip, 200);
        var catalog = new HttpKnowledgeBundleCatalog(base);

        Path download = catalog.downloadBundle(new CloudKnowledgeBundleManifest("b", "1.0.0", "t", zip.length, sha));
        try {
            assertArrayEquals(zip, Files.readAllBytes(download));
        } finally {
            Files.deleteIfExists(download);
        }
    }

    @Test
    void rejectsDownloadOnChecksumMismatch() throws Exception {
        byte[] zip = "zip-bytes".getBytes(StandardCharsets.UTF_8);
        URI base = start(new byte[0], 404, zip, 200);
        var catalog = new HttpKnowledgeBundleCatalog(base);

        CloudApiRequestException error = assertThrows(CloudApiRequestException.class,
            () -> catalog.downloadBundle(new CloudKnowledgeBundleManifest("b", "1.0.0", "t", zip.length, "wrong-sha")));
        assertEquals("KNOWLEDGE_BUNDLE_CHECKSUM_MISMATCH", error.code());
    }

    @Test
    void rejectsNonLoopbackHttp() {
        assertThrows(IllegalArgumentException.class,
            () -> new HttpKnowledgeBundleCatalog(URI.create("http://example.com")));
    }
}
