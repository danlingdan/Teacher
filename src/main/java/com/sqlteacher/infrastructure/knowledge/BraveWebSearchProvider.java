package com.sqlteacher.infrastructure.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.knowledge.WebSearchProvider;
import com.sqlteacher.domain.SqlTeacherException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class BraveWebSearchProvider implements WebSearchProvider {
    private final String apiKey;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public BraveWebSearchProvider(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override public boolean enabled() { return !apiKey.isBlank(); }

    @Override
    public List<WebSearchResult> search(String query, int limit) {
        if (!enabled()) return List.of();
        if (query == null || query.isBlank() || limit < 1 || limit > 10) throw new IllegalArgumentException("invalid web search request");
        URI endpoint = URI.create("https://api.search.brave.com/res/v1/web/search?q="
            + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8) + "&count=" + limit + "&safesearch=strict");
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json").header("X-Subscription-Token", apiKey).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new SqlTeacherException("WEB_SEARCH_FAILED", "Web search returned HTTP " + response.statusCode());
            List<WebSearchResult> results = new ArrayList<>();
            for (JsonNode item : mapper.readTree(response.body()).path("web").path("results")) {
                String title = item.path("title").asText("").trim();
                String url = item.path("url").asText("").trim();
                if (!title.isBlank() && !url.isBlank()) results.add(new WebSearchResult(title, URI.create(url), item.path("description").asText("")));
            }
            return List.copyOf(results);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SqlTeacherException("WEB_SEARCH_INTERRUPTED", "Web search was interrupted", error);
        } catch (SqlTeacherException error) { throw error; }
        catch (Exception error) { throw new SqlTeacherException("WEB_SEARCH_FAILED", "Web search is unavailable", error); }
    }
}
