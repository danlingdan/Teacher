package com.sqlteacher.infrastructure.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.support.*;
import com.sqlteacher.application.update.ApplicationBuildInfo;
import com.sqlteacher.infrastructure.system.AtomicJsonFile;
import com.sqlteacher.infrastructure.system.ConfiguredHttpClient;
import com.sqlteacher.application.system.GeneralSoftwareService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class HttpProblemReportService implements ProblemReportService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final URI baseUri;
    private final HttpClient client;
    private final Path identityFile;

    public HttpProblemReportService(URI cloudBaseUri, Path dataDirectory, GeneralSoftwareService system) {
        baseUri = cloudBaseUri;
        client = ConfiguredHttpClient.create(system, HttpClient.Redirect.NORMAL);
        identityFile = dataDirectory.toAbsolutePath().normalize().resolve("support/install-identity.json");
    }

    @Override public ProblemReportReceipt submit(ProblemReportDraft draft, Map<String, Object> diagnostics, String accessToken) {
        InstallationIdentity identity = AtomicJsonFile.read(identityFile, InstallationIdentity.class, null);
        if (identity == null) { identity = new InstallationIdentity(UUID.randomUUID().toString()); AtomicJsonFile.write(identityFile, identity); }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", draft.idempotencyKey()); body.put("installId", identity.id());
        body.put("type", draft.type().name()); body.put("severity", draft.severity().name()); body.put("summary", draft.summary());
        body.put("description", draft.description()); body.put("reproductionSteps", draft.reproductionSteps());
        body.put("expectedResult", draft.expectedResult()); body.put("actualResult", draft.actualResult()); body.put("contact", draft.contact());
        body.put("application", ApplicationBuildInfo.current()); body.put("diagnostics", diagnostics);
        return request("support/reports", "POST", body, accessToken, ProblemReportReceipt.class);
    }

    @Override public ProblemReportReceipt status(String reportId, String queryToken) {
        return request("support/reports/" + encodeSegment(reportId) + "?queryToken=" + encodeSegment(queryToken), "GET", null, null, ProblemReportReceipt.class);
    }

    private <T> T request(String path, String method, Object payload, String accessToken, Class<T> type) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve("/api/v1/" + path)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
            if (accessToken != null && !accessToken.isBlank()) builder.header("Authorization", "Bearer " + accessToken);
            if (payload == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8));
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length > 64 * 1024) throw new IllegalStateException("问题反馈服务暂时不可用");
            return JSON.readValue(response.body(), type);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("问题反馈已取消", error); }
        catch (IOException error) { throw new IllegalStateException("问题反馈服务暂时不可用", error); }
    }
    private static String encodeSegment(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private record InstallationIdentity(String id) { }
}
