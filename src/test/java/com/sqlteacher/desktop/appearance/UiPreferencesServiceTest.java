package com.sqlteacher.desktop.appearance;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UiPreferencesServiceTest {
    @Test
    void shouldPersistNonSensitiveAppearanceChoicesAndRecoverFromInvalidValues() throws Exception {
        Preferences node = Preferences.userRoot().node("sqlteacher-test/" + UUID.randomUUID());
        try {
            UiPreferencesService service = new UiPreferencesService(node);
            assertEquals(UiPreferencesSnapshot.defaults(), service.current());

            var chosen = new UiPreferencesSnapshot(
                UiTheme.LIGHT, UiFontChoice.CLASSIC, UiDensity.COMPACT, true
            );
            service.save(chosen);
            assertEquals(chosen, new UiPreferencesService(node).current());

            node.put("theme", "BROKEN");
            UiPreferencesSnapshot recovered = new UiPreferencesService(node).current();
            assertEquals(UiTheme.SYSTEM, recovered.theme());
            assertFalse(node.keys().length == 0);
        } finally {
            node.removeNode();
        }
    }

    @Test
    void shouldParseWindowsLightAndDarkThemeValues() {
        assertEquals(UiTheme.LIGHT, UiPreferencesService.parseWindowsTheme(
            "AppsUseLightTheme    REG_DWORD    0x1"
        ));
        assertEquals(UiTheme.DARK, UiPreferencesService.parseWindowsTheme(
            "AppsUseLightTheme    REG_DWORD    0x0"
        ));
        assertEquals(UiTheme.DARK, UiPreferencesService.parseWindowsTheme("unexpected"));
    }
}
