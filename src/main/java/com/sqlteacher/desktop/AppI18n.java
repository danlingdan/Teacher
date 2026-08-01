package com.sqlteacher.desktop;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Central ResourceBundle access for the desktop layer. Loads {@code i18n/messages}
 * for the active locale and falls back to Simplified Chinese when a key is missing.
 * FXML loaders inject {@link #bundle()} so {@code %key} references resolve.
 */
public final class AppI18n {
    private static final Locale FALLBACK = Locale.SIMPLIFIED_CHINESE;
    private static volatile Locale activeLocale = currentDefault();
    private static volatile ResourceBundle bundle = load(activeLocale);

    private AppI18n() { }

    public static Locale locale() { return activeLocale; }

    public static void setLocale(Locale locale) {
        Locale next = locale == null ? FALLBACK : locale;
        activeLocale = next;
        bundle = load(next);
    }

    public static ResourceBundle bundle() { return bundle; }

    public static String get(String key) {
        try { return bundle.getString(key); }
        catch (MissingResourceException error) { return key; }
    }

    public static String format(String key, Object... args) {
        return MessageFormat.format(get(key), args);
    }

    private static Locale currentDefault() {
        Locale system = Locale.getDefault();
        String language = system.getLanguage().toLowerCase(Locale.ROOT);
        return "en".equals(language) ? Locale.ENGLISH : FALLBACK;
    }

    private static ResourceBundle load(Locale locale) {
        try {
            return ResourceBundle.getBundle("i18n.messages", locale);
        } catch (MissingResourceException error) {
            try {
                return ResourceBundle.getBundle("i18n.messages", FALLBACK);
            } catch (MissingResourceException nested) {
                return ResourceBundle.getBundle("i18n.messages", Locale.ROOT);
            }
        }
    }
}
