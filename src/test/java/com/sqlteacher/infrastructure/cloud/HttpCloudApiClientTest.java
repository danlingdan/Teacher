package com.sqlteacher.infrastructure.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpCloudApiClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ASSIGNMENT_JSON = """
        {"id":"assignment-1","classroomId":"class-1","exerciseId":"select-1",\
        "title":"查询练习","createdAt":"2026-07-22T00:00:00Z"}
        """;

    private HttpServer server;
    private HttpCloudApiClient client;
    private String requestMethod;
    private String authorization;
    private JsonNode requestBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/classes/class-1/assignments", this::assignments);
        server.start();
        client = new HttpCloudApiClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldCreateAssignmentWithAuthenticatedRequest() {
        var assignment = client.createAssignment("access-token", "class-1", "select-1", "查询练习");

        assertEquals("POST", requestMethod);
        assertEquals("Bearer access-token", authorization);
        assertEquals("select-1", requestBody.get("exerciseId").asText());
        assertEquals("查询练习", requestBody.get("title").asText());
        assertEquals("assignment-1", assignment.id());
        assertEquals(Instant.parse("2026-07-22T00:00:00Z"), assignment.createdAt());
    }

    @Test
    void shouldListAssignmentsForClassMember() {
        var assignments = client.listAssignments("member-token", "class-1");

        assertEquals("GET", requestMethod);
        assertEquals("Bearer member-token", authorization);
        assertEquals(1, assignments.size());
        assertEquals("select-1", assignments.getFirst().exerciseId());
    }

    private void assignments(HttpExchange exchange) throws IOException {
        requestMethod = exchange.getRequestMethod();
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        requestBody = requestBytes.length == 0 ? null : JSON.readTree(requestBytes);
        String response = "GET".equals(requestMethod)
            ? "{\"assignments\":[" + ASSIGNMENT_JSON + "]}"
            : ASSIGNMENT_JSON;
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.close();
    }
}
