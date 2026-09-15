package com.sqlteacher.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v3.4.3 OKB-4: the knowledge bundle endpoints are unauthenticated and degrade to a clear 404
 * when the server has no bundle published (env unset). Serving real bytes is verified by the
 * post-deployment HTTPS smoke test because the file paths come from process environment.
 */
@Tag("integration")
class KnowledgeBundleCloudEndpointTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;
    private SqlTeacherCloudServer server;

    @AfterEach void stop() { if (server != null) server.stop(); }

    @Test void manifestAndDownloadReturn404WhenNotPublished() throws Exception {
        server = new SqlTeacherCloudServer(directory.resolve("cloud.db"), 0);
        server.start();
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        URI base = URI.create("http://127.0.0.1:" + server.port());

        HttpResponse<String> manifest = client.send(
            HttpRequest.newBuilder(base.resolve("/api/v1/app/knowledge-bundle-manifest")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(404, manifest.statusCode());
        JsonNode manifestBody = JSON.readTree(manifest.body());
        assertEquals("KNOWLEDGE_BUNDLE_UNAVAILABLE", manifestBody.get("code").asText());

        HttpResponse<String> download = client.send(
            HttpRequest.newBuilder(base.resolve("/api/v1/app/knowledge-bundle")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(404, download.statusCode());
        assertEquals("KNOWLEDGE_BUNDLE_UNAVAILABLE", JSON.readTree(download.body()).get("code").asText());
    }
}
