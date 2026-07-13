package com.sqlteacher.desktop.viewmodel;

import com.sqlteacher.application.ai.AiStatus;

/**
 * ViewModel for the Ollama / AI status area shown on the home page and AI assistant page.
 *
 * <p>The backend {@link AiStatus} exposes the application enum {@code AiAvailability}.
 * This ViewModel converts it into a {@link UiStatusLevel} via {@link UiStatusLevel#fromStatusName(String)},
 * passing only the enum name so the application enum type is never imported here.
 */
public record AiStatusViewModel(
    UiStatusLevel statusLevel,
    String provider,
    String endpoint,
    int modelCount,
    boolean available,
    String message
) {
    public AiStatusViewModel {
        statusLevel = statusLevel == null ? UiStatusLevel.UNKNOWN : statusLevel;
        provider = provider == null ? "" : provider;
        endpoint = endpoint == null ? "" : endpoint;
        message = message == null ? "" : message;
    }

    public static AiStatusViewModel from(AiStatus status) {
        String statusName = status.status() == null ? null : status.status().name();
        return new AiStatusViewModel(
            UiStatusLevel.fromStatusName(statusName),
            status.provider(),
            status.endpoint(),
            status.modelCount(),
            status.available(),
            status.message()
        );
    }

    static AiStatusViewModel unknown() {
        return new AiStatusViewModel(UiStatusLevel.UNKNOWN, "", "", 0, false, "");
    }
}
