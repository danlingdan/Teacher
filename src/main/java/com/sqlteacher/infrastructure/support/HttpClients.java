package com.sqlteacher.infrastructure.support;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Single construction point for every outbound JDK {@link HttpClient}.
 *
 * <p>All clients are pinned to HTTP/1.1. The JDK default negotiates HTTP/2, and for
 * plaintext {@code http://} targets the h2c upgrade path can deadlock POST request
 * bodies against fast loopback responders on some Windows JDK builds (see the fix in
 * commit 9563f5d). Every outbound target in this project (loopback Ollama, the HTTPS
 * cloud API, HTTPS update mirrors, search and web-content endpoints) supports
 * HTTP/1.1, so one pinned protocol is the simplest reliable configuration.</p>
 */
public final class HttpClients {

    private HttpClients() { }

    /**
     * Returns a builder pinned to HTTP/1.1 with the given connect timeout; use it when
     * additional builder options such as {@code followRedirects} are required.
     */
    public static HttpClient.Builder newBuilder(Duration connectTimeout) {
        return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout);
    }

    /** Returns a client pinned to HTTP/1.1 with the given connect timeout. */
    public static HttpClient create(Duration connectTimeout) {
        return newBuilder(connectTimeout).build();
    }
}
