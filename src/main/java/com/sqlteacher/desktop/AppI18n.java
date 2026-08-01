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
    // The default UI language is Simplified Chinese, independent of the OS locale,
    // so the product does not silently switch languages. The user can override it
    // explicitly in the "Updates & Support" settings page.
    private static volatile Locale activeLocale = FALLBACK;
    private static volatile ResourceBundle bundle = load(activeLocale);

    private AppI18n() { }

    public static Locale locale() { return activeLocale; }

    public static void setLocale(Locale locale) {
        Locale next = locale == null ? FALLBACK : locale;
        activeLocale = next;
        bundle = load(next);
    }

    /** Applies the persisted language preference ({@code "zh"} or {@code "en"}). */
    public static void applyLanguage(String language) {
        if (language != null && "en".equalsIgnoreCase(language.strip())) {
            setLocale(Locale.ENGLISH);
        } else {
            setLocale(FALLBACK);
        }
    }

    public static ResourceBundle bundle() { return bundle; }

    public static String get(String key) {
        try { return bundle.getString(key); }
        catch (MissingResourceException error) { return key; }
    }

    public static String format(String key, Object... args) {
        return MessageFormat.format(get(key), args);
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
