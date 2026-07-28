package com.sqlteacher.desktop.appearance;

import java.util.Objects;

public record UiPreferencesSnapshot(
    UiTheme theme,
    UiFontChoice font,
    UiDensity density,
    boolean reducedMotion
) {
    public UiPreferencesSnapshot {
        Objects.requireNonNull(theme);
        Objects.requireNonNull(font);
        Objects.requireNonNull(density);
    }

    public static UiPreferencesSnapshot defaults() {
        return new UiPreferencesSnapshot(UiTheme.SYSTEM, UiFontChoice.MODERN, UiDensity.COMFORTABLE, false);
    }
}
