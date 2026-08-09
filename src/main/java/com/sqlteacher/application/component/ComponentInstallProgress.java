package com.sqlteacher.application.component;

import java.util.Objects;

public record ComponentInstallProgress(double fraction, String message) {
    public ComponentInstallProgress {
        if (Double.isNaN(fraction) || fraction < -1 || fraction > 1) {
            throw new IllegalArgumentException("fraction must be -1 or between 0 and 1");
        }
        message = Objects.requireNonNullElse(message, "").trim();
    }
}
