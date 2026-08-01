package com.sqlteacher.infrastructure.knowledge;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdkSafeWebContentFetcherTest {
    @Test void acceptsPublicHttpUrls() { assertEquals("https", JdkSafeWebContentFetcher.validate(URI.create("https://example.com/a")).getScheme()); }
    @Test void rejectsLocalAndCredentialedUrls() {
        assertThrows(IllegalArgumentException.class, () -> JdkSafeWebContentFetcher.validate(URI.create("http://localhost/admin")));
        assertThrows(IllegalArgumentException.class, () -> JdkSafeWebContentFetcher.validate(URI.create("https://user:pass@example.com/")));
    }
}
