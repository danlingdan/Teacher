package com.sqlteacher.infrastructure.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.collaboration.CloudApiRequestException;
import com.sqlteacher.application.knowledge.CloudKnowledgeBundleManifest;
import com.sqlteacher.application.knowledge.KnowledgeBundleCatalog;
import com.sqlteacher.infrastructure.support.HttpClients;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * HTTPS client for the official knowledge bundle endpoints (v3.4.3 OKB-4). HTTP is accepted only
 * for loopback integration tests, mirroring {@link HttpCloudApiClient}. Downloads stream to a
 * temporary file and are verified against the manifest's size and sha256 before being returned.
 */
public final class HttpKnowledgeBundleCatalog implements KnowledgeBundleCatalog {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MANIFEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);
    private static final long MAX_BUNDLE_BYTES = 512L * 1024 * 1024;

    private final URI baseUri;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();

    public HttpKnowledgeBundleCatalog(URI baseUri) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
        if (!"https".equalsIgnoreCase(baseUri.getScheme())) {
            if (!"http".equalsIgnoreCase(baseUri.getScheme()) || !isLoopback(baseUri)) {
                throw new IllegalArgumentException("Knowledge bundle catalog must use HTTPS; HTTP is allowed only for loopback tests");
            }
        }
        this.client = HttpClients.newBuilder(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public Optional<CloudKnowledgeBundleManifest> fetchManifest() {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/v1/app/knowledge-bundle-manifest"))
            .timeout(MANIFEST_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new CloudApiRequestException(response.statusCode(), "KNOWLEDGE_BUNDLE_CHECK_FAILED",
                    "Knowledge bundle manifest request failed (HTTP " + response.statusCode() + ")");
            }
            JsonNode node = json.readTree(response.body());
            return Optional.of(new CloudKnowledgeBundleManifest(
                node.path("bundleId").asText(),
                node.path("version").asText(),
                node.path("title").asText(""),
                node.path("sizeBytes").asLong(0),
                node.path("sha256").asText()));
        } catch (IOException error) {
            throw new CloudApiRequestException(503, "CLOUD_UNAVAILABLE", "Cloud knowledge bundle is unavailable", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Knowledge bundle manifest request was interrupted", error);
        }
    }

    @Override
    public Path downloadBundle(CloudKnowledgeBundleManifest manifest) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        if (manifest.sizeBytes() > MAX_BUNDLE_BYTES) {
            throw new CloudApiRequestException(413, "KNOWLEDGE_BUNDLE_TOO_LARGE", "Knowledge bundle exceeds the size limit");
        }
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/v1/app/knowledge-bundle"))
            .timeout(DOWNLOAD_TIMEOUT)
            .header("Accept", "application/zip")
            .GET()
            .build();
        Path target;
        try {
            target = Files.createTempFile("sqlteacher-bundle-", ".zip");
            Files.deleteIfExists(target);
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                Files.deleteIfExists(target);
                throw new CloudApiRequestException(response.statusCode(), "KNOWLEDGE_BUNDLE_DOWNLOAD_FAILED",
                    "Knowledge bundle download failed (HTTP " + response.statusCode() + ")");
            }
        } catch (IOException error) {
            throw new CloudApiRequestException(503, "CLOUD_UNAVAILABLE", "Cloud knowledge bundle is unavailable", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Knowledge bundle download was interrupted", error);
        }

        try {
            byte[] bytes = Files.readAllBytes(target);
            if (manifest.sizeBytes() > 0 && bytes.length != manifest.sizeBytes()) {
                throw new CloudApiRequestException(502, "KNOWLEDGE_BUNDLE_CHECKSUM_MISMATCH",
                    "Knowledge bundle size does not match the manifest");
            }
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equalsIgnoreCase(manifest.sha256())) {
                throw new CloudApiRequestException(502, "KNOWLEDGE_BUNDLE_CHECKSUM_MISMATCH",
                    "Knowledge bundle checksum does not match the manifest");
            }
            return target;
        } catch (IOException | NoSuchAlgorithmException error) {
            deleteQuietly(target);
            throw new CloudApiRequestException(502, "KNOWLEDGE_BUNDLE_DOWNLOAD_FAILED",
                "Knowledge bundle verification failed", error);
        } catch (RuntimeException error) {
            deleteQuietly(target);
            throw error;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    private static boolean isLoopback(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException error) {
            return false;
        }
    }
}
