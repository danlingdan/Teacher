package com.sqlteacher.application.ermodel;

import com.sqlteacher.domain.ermodel.ErModel;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of one E-R modeling generation crossing the service boundary. {@code erModel} is
 * non-null only for {@link ErGenerationStatus#OK}; failures never carry a partially validated
 * model. {@code errors} carries the aggregated deterministic validation reasons and is empty
 * otherwise; {@code message} is a redacted user-facing Chinese summary that never echoes the
 * requirement text or the raw model output.
 */
public record ErModelingResult(
    ErGenerationStatus status,
    String model,
    String promptVersion,
    ErModel erModel,
    List<String> errors,
    String message
) {
    public ErModelingResult {
        Objects.requireNonNull(status, "status must not be null");
        errors = errors == null ? List.of() : List.copyOf(errors);
        message = message == null ? "" : message;
        if (status != ErGenerationStatus.OK) {
            erModel = null;
        }
    }

    public static ErModelingResult ok(String model, String promptVersion, ErModel erModel, String message) {
        return new ErModelingResult(ErGenerationStatus.OK, model, promptVersion, erModel, List.of(), message);
    }

    public static ErModelingResult failure(ErGenerationStatus status, String model, String promptVersion,
                                           String message, List<String> errors) {
        return new ErModelingResult(status, model, promptVersion, null, errors, message);
    }
}
