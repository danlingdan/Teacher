package com.sqlteacher.application.knowledge;

import java.net.URI;
import java.util.List;

public interface WebSearchProvider {
    boolean enabled();
    List<WebSearchResult> search(String query, int limit);

    record WebSearchResult(String title, URI uri, String snippet) {
        public WebSearchResult {
            if (title == null || title.isBlank() || uri == null || snippet == null) {
                throw new IllegalArgumentException("web search result values are invalid");
            }
            snippet = snippet.trim();
        }
    }
}
