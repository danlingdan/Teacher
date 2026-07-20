package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.AiModelSelection;
import com.sqlteacher.application.ai.AiModelSelectionService;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.domain.SqlTeacherException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Ollama-backed model discovery and persistent local model preference. */
public final class OllamaModelSelectionService implements AiModelSelectionService {
    private final AiConfiguration properties;
    private final Path preferenceFile;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private volatile AiModelSelection current =
        new AiModelSelection(List.of(), "", "Models have not been detected");
    private String preferredModel;

    public OllamaModelSelectionService(AiConfiguration properties, Path preferenceFile) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.preferenceFile = Objects.requireNonNull(preferenceFile, "preferenceFile must not be null");
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.healthTimeout())
            .build();
        this.objectMapper = new ObjectMapper();
        this.preferredModel = readPreference();
    }

    @Override
    public synchronized AiModelSelection refresh() {
        HttpRequest request = HttpRequest.newBuilder(properties.tagsEndpoint())
            .GET()
            .timeout(properties.healthTimeout())
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                current = unavailable("Ollama returned HTTP " + response.statusCode());
                return current;
            }

            List<String> models = parseModels(response.body());
            String selected = chooseSelection(models);
            String message = models.isEmpty()
                ? "Ollama is running, but no local model is installed"
                : "Detected " + models.size() + " local model(s)";
            current = new AiModelSelection(models, selected, message);
            return current;
        } catch (IOException ex) {
            current = unavailable("Ollama service unavailable: " + ex.getClass().getSimpleName());
            return current;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            current = unavailable("Ollama model detection interrupted");
            return current;
        }
    }

    @Override
    public AiModelSelection current() {
        return current;
    }

    @Override
    public synchronized AiModelSelection select(String model) {
        String normalized = model == null ? "" : model.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        AiModelSelection snapshot = current;
        if (!snapshot.installedModels().contains(normalized)) {
            snapshot = refresh();
        }
        if (!snapshot.installedModels().contains(normalized)) {
            throw new IllegalArgumentException("model is not installed: " + normalized);
        }

        preferredModel = normalized;
        persistPreference(normalized);
        current = new AiModelSelection(snapshot.installedModels(), normalized, "Selected model: " + normalized);
        return current;
    }

    private List<String> parseModels(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        JsonNode models = root.path("models");
        if (models.isArray()) {
            for (JsonNode model : models) {
                String name = model.path("name").asText("").strip();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return List.copyOf(new ArrayList<>(names));
    }

    private String chooseSelection(List<String> models) {
        String active = current.selectedModel();
        if (models.contains(active)) {
            return active;
        }
        if (models.contains(preferredModel)) {
            return preferredModel;
        }
        if (models.contains(properties.defaultModel())) {
            return properties.defaultModel();
        }
        return models.isEmpty() ? "" : models.getFirst();
    }

    private AiModelSelection unavailable(String message) {
        return new AiModelSelection(List.of(), "", message);
    }

    private String readPreference() {
        if (!Files.isRegularFile(preferenceFile)) {
            return "";
        }
        try {
            return Files.readString(preferenceFile, StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            return "";
        }
    }

    private void persistPreference(String model) {
        try {
            Path parent = preferenceFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(preferenceFile, model + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new SqlTeacherException(
                "AI_MODEL_PREFERENCE_WRITE_FAILED",
                "Failed to save the selected AI model",
                ex
            );
        }
    }
}
