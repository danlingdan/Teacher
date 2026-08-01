package com.sqlteacher.desktop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locale gate for v1.11 full English UI: the Chinese and English bundles must have
 * exactly the same keys, every {@code %key} referenced by an FXML file must resolve,
 * and switching locales must actually change resolved text.
 */
class AppI18nTest {
    private static final Pattern FXML_KEY = Pattern.compile("%([a-zA-Z][a-zA-Z0-9_.]*)");

    @Test void chineseAndEnglishBundlesHaveIdenticalKeySets() throws Exception {
        Properties zh = load("messages_zh_CN.properties");
        Properties en = load("messages_en.properties");
        Set<String> zhKeys = zh.stringPropertyNames();
        Set<String> enKeys = en.stringPropertyNames();
        Set<String> missingInEn = new HashSet<>(zhKeys);
        missingInEn.removeAll(enKeys);
        Set<String> missingInZh = new HashSet<>(enKeys);
        missingInZh.removeAll(zhKeys);
        assertTrue(missingInEn.isEmpty(), "keys missing in English bundle: " + missingInEn);
        assertTrue(missingInZh.isEmpty(), "keys missing in Chinese bundle: " + missingInZh);
        assertTrue(zhKeys.size() >= 90, "bundle should have grown beyond the v1.10 baseline, size=" + zhKeys.size());
    }

    @Test void everyFxmlKeyReferenceResolvesInBothBundles() throws Exception {
        Properties zh = load("messages_zh_CN.properties");
        Properties en = load("messages_en.properties");
        Set<String> missing = new HashSet<>();
        try (Stream<Path> fxmlFiles = Files.walk(Path.of(resourcePath("fxml")))) {
            for (Path file : fxmlFiles.filter(path -> path.toString().endsWith(".fxml")).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = FXML_KEY.matcher(content);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (!zh.containsKey(key)) missing.add(key + " (zh)");
                    if (!en.containsKey(key)) missing.add(key + " (en)");
                }
            }
        }
        assertTrue(missing.isEmpty(), "FXML %key references without bundle entries: " + missing);
    }

    @Test void localeSwitchActuallyChangesResolvedText() {
        AppI18n.setLocale(Locale.SIMPLIFIED_CHINESE);
        String zh = AppI18n.get("nav.home");
        AppI18n.setLocale(Locale.ENGLISH);
        String en = AppI18n.get("nav.home");
        assertNotEquals(zh, en, "locales must produce different resolved text");
        assertEquals("Home", en);
        assertFalse(zh.isBlank());
    }

    @Test void missingKeyFallsBackToTheKeyItselfWithoutThrowing() {
        AppI18n.setLocale(Locale.ENGLISH);
        assertEquals("no.such.key", AppI18n.get("no.such.key"));
        AppI18n.setLocale(Locale.SIMPLIFIED_CHINESE);
    }

    @Test void defaultsToSimplifiedChineseIndependentOfSystemLocale() {
        AppI18n.applyLanguage(null);
        assertEquals("首页", AppI18n.get("nav.home"));
        AppI18n.applyLanguage("");
        assertEquals("首页", AppI18n.get("nav.home"));
        AppI18n.applyLanguage("zh");
        assertEquals("首页", AppI18n.get("nav.home"));
    }

    @Test void applyLanguageSwitchesToEnglishAndBack() {
        AppI18n.applyLanguage("en");
        assertEquals("Home", AppI18n.get("nav.home"));
        AppI18n.applyLanguage("zh");
        assertEquals("首页", AppI18n.get("nav.home"));
        AppI18n.applyLanguage("EN"); // case-insensitive
        assertEquals("Home", AppI18n.get("nav.home"));
        AppI18n.applyLanguage("zh");
    }

    private static Properties load(String file) throws IOException {
        try (InputStream stream = AppI18nTest.class.getResourceAsStream("/i18n/" + file)) {
            assertNotNull(stream, "missing bundle resource: " + file);
            Properties properties = new Properties();
            properties.load(stream);
            return properties;
        }
    }

    private static String resourcePath(String relative) throws URISyntaxException {
        return Path.of(AppI18nTest.class.getResource("/" + relative).toURI()).toString();
    }
}
