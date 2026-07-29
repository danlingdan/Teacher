package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.*;

import java.net.URI;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded model discovery for Ollama and OpenAI-compatible endpoints. */
public final class HttpAiProviderProbeService implements AiProviderProbeService {
    private static final int MAX_BODY = 256_000;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpAiProviderProbeService() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    HttpAiProviderProbeService(HttpClient client) { this.client = client; }

    @Override public AiProviderProbeResult probe(AiProviderProfileDraft profile, char[] credential) {
        URI target = modelsEndpoint(profile);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10)).GET()
            .header("Accept", "application/json");
        try {
            if (profile.kind() == AiProviderKind.OPENAI_COMPATIBLE && credential != null && credential.length > 0) {
                builder.header("Authorization", "Bearer " + new String(credential));
            }
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 401 || response.statusCode() == 403)
                { response.body().close(); return fail(AiTaskErrorCode.AUTHENTICATION_FAILED, "认证失败，请检查 API Key。"); }
            if (response.statusCode() == 429)
                { response.body().close(); return fail(AiTaskErrorCode.RATE_LIMITED, "Provider 正在限流，请稍后重试。"); }
            if (response.statusCode() >= 300 && response.statusCode() < 400)
                { response.body().close(); return fail(AiTaskErrorCode.SAFETY_REJECTED, "Provider 返回重定向；为防止凭据泄露，请直接填写最终 HTTPS 地址。"); }
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                { response.body().close(); return fail(AiTaskErrorCode.PROVIDER_UNAVAILABLE, "Provider 返回 HTTP " + response.statusCode() + "。"); }
            byte[] bytes;
            try (InputStream input = response.body()) { bytes = input.readNBytes(MAX_BODY + 1); }
            if (bytes.length > MAX_BODY)
                return fail(AiTaskErrorCode.RESPONSE_TOO_LARGE, "模型列表响应过大，已拒绝读取。");
            List<String> models = parseModels(profile.kind(), new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            return models.isEmpty()
                ? fail(AiTaskErrorCode.MODEL_NOT_FOUND, "连接成功，但没有发现可用模型。")
                : new AiProviderProbeResult(true, models, "连接成功，发现 " + models.size() + " 个模型。", null);
        } catch (java.net.http.HttpTimeoutException error) {
            return fail(AiTaskErrorCode.TIMED_OUT, "连接测试超时。");
        } catch (Exception error) {
            return fail(AiTaskErrorCode.PROVIDER_UNAVAILABLE, "无法连接 Provider，请检查地址、证书和网络。");
        } finally {
            if (credential != null) Arrays.fill(credential, '\0');
        }
    }

    private List<String> parseModels(AiProviderKind kind, String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode array = kind == AiProviderKind.OLLAMA ? root.path("models") : root.path("data");
        List<String> result = new ArrayList<>();
        if (array.isArray()) for (JsonNode item : array) {
            String name = kind == AiProviderKind.OLLAMA ? item.path("name").asText() : item.path("id").asText();
            if (!name.isBlank() && name.length() <= 256 && result.size() < 200) result.add(name);
        }
        return result.stream().distinct().sorted().toList();
    }

    private static URI modelsEndpoint(AiProviderProfileDraft profile) {
        URI endpoint = profile.endpoint();
        if (profile.kind() == AiProviderKind.OLLAMA) return endpoint.resolve("/api/tags");
        String path = endpoint.getPath();
        if (path.endsWith("/chat/completions")) path = path.substring(0, path.length() - "/chat/completions".length()) + "/models";
        else if (!path.endsWith("/models")) path = (path.endsWith("/") ? path : path + "/") + "models";
        try { return new URI(endpoint.getScheme(), endpoint.getUserInfo(), endpoint.getHost(), endpoint.getPort(), path, null, null); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid provider endpoint", error); }
    }

    private static AiProviderProbeResult fail(AiTaskErrorCode code, String message) {
        return new AiProviderProbeResult(false, List.of(), message, code);
    }
}
