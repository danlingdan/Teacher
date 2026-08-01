package com.sqlteacher.application.knowledge;

import java.net.URI;
import java.time.Instant;

public interface SafeWebContentFetcher {
    FetchedWebContent fetch(URI uri);

    record FetchedWebContent(URI finalUri, String title, String text, String contentHash, Instant fetchedAt) {
        public FetchedWebContent {
            if (finalUri == null || title == null || title.isBlank() || text == null || text.isBlank()
                || contentHash == null || contentHash.isBlank() || fetchedAt == null) {
                throw new IllegalArgumentException("fetched web content values are invalid");
            }
        }
    }
}
