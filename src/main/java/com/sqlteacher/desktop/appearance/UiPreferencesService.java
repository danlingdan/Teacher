package com.sqlteacher.desktop.appearance;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.prefs.Preferences;
import java.util.concurrent.TimeUnit;

/** Stores non-sensitive device UI preferences and applies them to every open JavaFX scene. */
public final class UiPreferencesService {
    private static final Logger LOG = LoggerFactory.getLogger(UiPreferencesService.class);
    private static final String BASE_CSS = "/css/app.css";
    private static final String LIGHT_CSS = "/css/theme-light.css";
    private static final List<String> ROOT_CLASSES = List.of(
        "theme-dark", "theme-light", "font-modern", "font-system", "font-classic",
        "density-comfortable", "density-compact", "reduced-motion"
    );
    private static final UiTheme SYSTEM_THEME = detectSystemTheme();
    private static final UiPreferencesService SHARED = new UiPreferencesService();

    private final Preferences preferences;
    private UiPreferencesSnapshot current;

    public UiPreferencesService() {
        this(Preferences.userNodeForPackage(UiPreferencesService.class));
    }

    public static UiPreferencesService shared() {
        return SHARED;
    }

    UiPreferencesService(Preferences preferences) {
        this.preferences = Objects.requireNonNull(preferences);
        this.current = load();
    }

    public UiPreferencesSnapshot current() {
        return current;
    }

    public void save(UiPreferencesSnapshot snapshot) {
        current = Objects.requireNonNull(snapshot);
        preferences.put("theme", snapshot.theme().name());
        preferences.put("font", snapshot.font().name());
        preferences.put("density", snapshot.density().name());
        preferences.putBoolean("reducedMotion", snapshot.reducedMotion());
        Window.getWindows().stream().map(Window::getScene).filter(Objects::nonNull).forEach(this::apply);
    }

    public void reset() {
        save(UiPreferencesSnapshot.defaults());
    }

    public void apply(Scene scene) {
        Objects.requireNonNull(scene);
        addStylesheet(scene, BASE_CSS);
        scene.getStylesheets().removeIf(value -> value.endsWith("theme-light.css"));
        UiTheme resolved = resolve(current.theme());
        if (resolved == UiTheme.LIGHT) addStylesheet(scene, LIGHT_CSS);
        scene.setFill(resolved == UiTheme.LIGHT ? Color.web("#f4f7fb") : Color.web("#141c30"));
        Parent root = scene.getRoot();
        root.getStyleClass().removeAll(ROOT_CLASSES);
        root.getStyleClass().add(resolved == UiTheme.LIGHT ? "theme-light" : "theme-dark");
        root.getStyleClass().add(current.font().styleClass());
        root.getStyleClass().add(current.density().styleClass());
        if (current.reducedMotion()) root.getStyleClass().add("reduced-motion");
    }

    private UiPreferencesSnapshot load() {
        return new UiPreferencesSnapshot(
            readEnum("theme", UiTheme.class, UiTheme.SYSTEM),
            readEnum("font", UiFontChoice.class, UiFontChoice.MODERN),
            readEnum("density", UiDensity.class, UiDensity.COMFORTABLE),
            preferences.getBoolean("reducedMotion", false)
        );
    }

    private <T extends Enum<T>> T readEnum(String key, Class<T> type, T fallback) {
        try {
            return Enum.valueOf(type, preferences.get(key, fallback.name()));
        } catch (IllegalArgumentException error) {
            return fallback;
        }
    }

    private static UiTheme resolve(UiTheme requested) {
        return requested == UiTheme.SYSTEM ? SYSTEM_THEME : requested;
    }

    private static UiTheme detectSystemTheme() {
        String override = System.getProperty("sqlteacher.system-theme",
            System.getenv().getOrDefault("SQLTEACHER_SYSTEM_THEME", ""));
        if (!override.isBlank()) {
            return override.toLowerCase(Locale.ROOT).startsWith("light") ? UiTheme.LIGHT : UiTheme.DARK;
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return UiTheme.DARK;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(
                "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v", "AppsUseLightTheme"
            ).redirectErrorStream(true).start();
            if (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return UiTheme.DARK;
            }
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return parseWindowsTheme(output);
        } catch (Exception error) {
            if (process != null) process.destroyForcibly();
            LOG.debug("Unable to detect Windows app theme; using dark fallback", error);
            return UiTheme.DARK;
        }
    }

    static UiTheme parseWindowsTheme(String output) {
        if (output == null) return UiTheme.DARK;
        String normalized = output.toLowerCase(Locale.ROOT);
        return normalized.matches("(?s).*appsuselighttheme\\s+reg_dword\\s+0x0*1(?:\\s|$).*")
            ? UiTheme.LIGHT : UiTheme.DARK;
    }

    private static void addStylesheet(Scene scene, String path) {
        URL resource = UiPreferencesService.class.getResource(path);
        if (resource != null && !scene.getStylesheets().contains(resource.toExternalForm())) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
    }
}
