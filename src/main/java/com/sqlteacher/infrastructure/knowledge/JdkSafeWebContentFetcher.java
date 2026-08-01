package com.sqlteacher.infrastructure.knowledge;

import com.sqlteacher.application.knowledge.SafeWebContentFetcher;
import com.sqlteacher.domain.SqlTeacherException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

public final class JdkSafeWebContentFetcher implements SafeWebContentFetcher {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final Set<String> TYPES = Set.of("text/html", "text/plain", "application/xhtml+xml");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    @Override
    public FetchedWebContent fetch(URI requestedUri) {
        URI uri = validate(requestedUri);
        try {
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                validateResolvedAddresses(uri);
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
                    .header("Accept", "text/html,text/plain;q=0.9").header("User-Agent", "SQLTeacher/1.8.5 knowledge-fetcher").GET().build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    if (redirect == MAX_REDIRECTS) throw new SqlTeacherException("WEB_FETCH_REDIRECT_LIMIT", "Too many web redirects");
                    String location = response.headers().firstValue("location").orElseThrow();
                    uri = validate(uri.resolve(location));
                    continue;
                }
                if (response.statusCode() != 200) throw new SqlTeacherException("WEB_FETCH_FAILED", "Web page returned HTTP " + response.statusCode());
                byte[] bytes = response.body();
                if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new SqlTeacherException("WEB_FETCH_SIZE_REJECTED", "Web page size is not allowed");
                String type = response.headers().firstValue("content-type").orElse("text/plain").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!TYPES.contains(type)) throw new SqlTeacherException("WEB_FETCH_TYPE_REJECTED", "Web content type is not allowed");
                String raw = new String(bytes, StandardCharsets.UTF_8);
                String title;
                String text;
                if (type.equals("text/plain")) { title = uri.getHost(); text = raw; }
                else {
                    Document document = Jsoup.parse(raw, uri.toString());
                    document.select("script,style,noscript,iframe,object,embed,form,nav,footer").remove();
                    title = document.title().isBlank() ? uri.getHost() : document.title();
                    text = document.body().text();
                }
                text = text.replaceAll("\\s+", " ").trim();
                if (text.isBlank()) throw new SqlTeacherException("WEB_FETCH_EMPTY", "Web page has no readable text");
                if (text.length() > 120_000) text = text.substring(0, 120_000);
                return new FetchedWebContent(uri, title, text, sha256(text), Instant.now());
            }
            throw new SqlTeacherException("WEB_FETCH_FAILED", "Web fetch failed");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SqlTeacherException("WEB_FETCH_INTERRUPTED", "Web fetch was interrupted", error);
        } catch (SqlTeacherException error) { throw error; }
        catch (Exception error) { throw new SqlTeacherException("WEB_FETCH_FAILED", "Unable to fetch web content safely", error); }
    }

    static URI validate(URI uri) {
        if (uri == null || uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
            || uri.getUserInfo() != null || uri.getPort() < -1) throw new IllegalArgumentException("Only public HTTP(S) URLs are allowed");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local"))
            throw new IllegalArgumentException("Local network URLs are not allowed");
        return uri.normalize();
    }

    private static void validateResolvedAddresses(URI uri) throws Exception {
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress() || reserved(address))
                throw new IllegalArgumentException("Private or reserved network addresses are not allowed");
        }
    }

    private static boolean reserved(InetAddress address) {
        byte[] value = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = value[0] & 255, second = value[1] & 255;
            return first == 0 || first == 10 || first == 127 || first >= 224 || first == 169 && second == 254
                || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168
                || first == 100 && second >= 64 && second <= 127 || first == 198 && (second == 18 || second == 19);
        }
        return address instanceof Inet6Address && (value[0] & 0xfe) == 0xfc;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
