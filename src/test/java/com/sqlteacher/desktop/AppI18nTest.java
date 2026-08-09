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
import java.util.Map;
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
    private static final Pattern FXML_KEY = Pattern.compile("%([a-zA-Z][a-zA-Z0-9_.-]*)");

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

    @Test void englishBundleMustNotContainChineseValues() throws Exception {
        Properties en = load("messages_en.properties");
        java.util.regex.Pattern cjk = java.util.regex.Pattern.compile("[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]");
        java.util.List<String> offenders = en.stringPropertyNames().stream()
            .filter(key -> cjk.matcher(en.getProperty(key)).find())
            .map(key -> key + "=" + en.getProperty(key))
            .toList();
        assertTrue(offenders.isEmpty(), "English bundle must not contain Chinese values: " + offenders);
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

    @Test void primaryLearningFlowCopyAvoidsInternalImplementationVocabulary() throws Exception {
        Properties zh = load("messages_zh_CN.properties");
        Properties en = load("messages_en.properties");
        Set<String> learningFlowKeys = Set.of(
            "HomeController.2", "HomeController.8", "alpha2.context.workspace",
            "alpha2.context.ai", "alpha2.context.student", "alpha2.context.progress",
            "alpha2.workspace.summary", "alpha3.evaluating", "alpha3.loading.message",
            "alpha3.unsupported.message", "alpha3.review.loading", "alpha3.review.required",
            "simulation.offline", "simulation.checkpoints"
        );
        Map<Properties, Set<String>> forbiddenByBundle = Map.of(
            zh, Set.of("确定性", "评价器", "评测器", "活动定义", "安全边界", "证据状态"),
            en, Set.of("deterministic", "evaluator", "activity definition", "safety boundary", "evidence state")
        );
        Set<String> offenders = new HashSet<>();
        forbiddenByBundle.forEach((bundle, forbiddenTerms) -> learningFlowKeys.forEach(key -> {
            String value = bundle.getProperty(key, "").toLowerCase(Locale.ROOT);
            forbiddenTerms.stream()
                .filter(term -> value.contains(term.toLowerCase(Locale.ROOT)))
                .forEach(term -> offenders.add(key + " contains " + term));
        }));
        assertTrue(offenders.isEmpty(),
            "Primary learning flow copy should describe the learner's task, feedback, or next action: " + offenders);
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
