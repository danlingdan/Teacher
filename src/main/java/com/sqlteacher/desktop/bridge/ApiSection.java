package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.DesktopAccessProfile;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * v3.4.0 REF-8: shared plumbing for business-domain sections. Bodies of moved handlers stay
 * byte-for-byte equivalent: sections keep the mapper field, call {@link #context()} per request
 * exactly like the old facade code, and reuse the same parameter parsing and role guards.
 */
abstract class ApiSection implements LocalAppApiSection {

    private final ApiSectionHost host;
    protected final ObjectMapper mapper;

    protected ApiSection(ApiSectionHost host) {
        this.host = host;
        this.mapper = host.mapper();
    }

    protected final AnnotationConfigApplicationContext context() {
        return host.context();
    }

    protected final boolean coreInitialized() {
        return host.coreInitialized();
    }

    protected DesktopAccessProfile currentAccessProfile() {
        return context().getBean(CloudSessionService.class).current()
            .map(DesktopAccessProfile::from).orElseGet(DesktopAccessProfile::guest);
    }

    protected DesktopAccessProfile requireTeacher() {
        DesktopAccessProfile profile = currentAccessProfile();
        if (profile.kind() != DesktopAccessProfile.Kind.TEACHER
                && profile.kind() != DesktopAccessProfile.Kind.ADMIN) {
            throw new SecurityException("Teaching workspace requires teacher or administrator role");
        }
        return profile;
    }

    protected com.sqlteacher.application.collaboration.CloudAuthenticationService.Session requireCloudSession() {
        return context().getBean(CloudSessionService.class).current()
            .orElseThrow(() -> new SecurityException("An authenticated cloud session is required"));
    }

    protected void requireLocalMaintenance() {
        if (!currentAccessProfile().canConfigure(
                com.sqlteacher.application.collaboration.DesktopSettingPermission.LOCAL_DATA_MAINTENANCE)) {
            throw new SecurityException("Local data maintenance is not allowed for the current role");
        }
    }

    /** Shared by the system and account sections: the session node of the frozen contract. */
    protected ObjectNode currentSession() {
        DesktopAccessProfile profile = currentAccessProfile();
        ObjectNode result = mapper.createObjectNode();
        result.put("subjectId", profile.isGuest() ? "guest" : profile.userId());
        result.put("displayName", profile.displayName());
        result.put("role", webRole(profile));
        result.put("authenticated", !profile.isGuest());
        result.put("roleLabel", profile.roleLabel());
        ArrayNode permissions = result.putArray("permissions");
        profile.capabilities().stream().map(Enum::name).sorted().forEach(permissions::add);
        return result;
    }

    protected void emit(Consumer<LocalAppEvent> events, String type, String field, String value) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put(field, value);
        events.accept(new LocalAppEvent(type, payload));
    }

    protected static String webRole(DesktopAccessProfile profile) {
        return switch (profile.kind()) {
            case ADMIN -> "ADMINISTRATOR";
            case TEACHER -> "TEACHER";
            case STUDENT, GUEST -> "STUDENT";
        };
    }

    protected static String requiredText(JsonNode params, String field, int maxLength) {
        String value = params.path(field).asText("").trim();
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain at most " + maxLength + " characters");
        }
        return value;
    }

    protected static String requiredRawText(JsonNode params, String field, int maxLength) {
        String value = params.path(field).asText("");
        if (value.isEmpty() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain at most " + maxLength + " characters");
        }
        return value;
    }

    protected static List<String> textList(JsonNode node, int maximumItems, int maximumLength) {
        if (!node.isArray() || node.size() > maximumItems) {
            throw new IllegalArgumentException("Expected a bounded text list");
        }
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("").trim();
            if (value.isEmpty() || value.length() > maximumLength) {
                throw new IllegalArgumentException("Text list contains an invalid value");
            }
            result.add(value);
        });
        return List.copyOf(result);
    }

    protected static Map<String, String> textMap(JsonNode node, int maximumItems, int maximumValueLength) {
        if (!node.isObject() || node.size() > maximumItems) {
            throw new IllegalArgumentException("Expected a bounded text map");
        }
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey().trim();
            String value = entry.getValue().asText("").trim();
            if (key.isEmpty() || key.length() > 128 || value.length() > maximumValueLength) {
                throw new IllegalArgumentException("Text map contains an invalid value");
            }
            result.put(key, value);
        });
        return Map.copyOf(result);
    }

    protected static Instant optionalInstant(JsonNode params, String field) {
        String value = params.path(field).asText("").trim();
        return value.isEmpty() ? null : Instant.parse(value);
    }

    protected static String optionalErrorCode(JsonNode params) {
        String value = params.path("errorCode").asText("").trim().toUpperCase();
        return value.isEmpty() ? null : value;
    }
}
