package com.sqlteacher.desktop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression gate for the "settings page cannot open" bug: an {@code FXMLLoader}
 * that loads a localized FXML (containing {@code %key} references) MUST inject a
 * ResourceBundle, otherwise JavaFX throws at load time. This test scans the source
 * tree so the gate works without a JavaFX toolkit.
 */
class FxmlBundleInjectionTest {
    private static final Pattern LOADER = Pattern.compile("new\\s+FXMLLoader\\s*\\(");
    private static final Pattern FXML_REF = Pattern.compile("/fxml/[A-Za-z0-9_-]+\\.fxml");

    @Test void everyLoaderForLocalizedFxmlInjectsBundle() throws Exception {
        Path sourceRoot = Path.of("src/main/java");
        Path resourceRoot = Path.of("src/main/resources");
        List<String> failures = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file);
                if (!content.contains("FXMLLoader")) continue;

                Set<String> localizedFxml = new HashSet<>();
                Matcher refMatcher = FXML_REF.matcher(content);
                while (refMatcher.find()) {
                    Path fxml = resourceRoot.resolve(refMatcher.group().substring(1));
                    if (Files.isRegularFile(fxml) && Files.readString(fxml).contains("%")) {
                        localizedFxml.add(refMatcher.group());
                    }
                }
                if (localizedFxml.isEmpty()) continue;

                boolean hasBareLoader = false;
                for (String line : content.lines().toList()) {
                    if (LOADER.matcher(line).find() && !line.contains("bundle") && !line.contains("ResourceBundle")) {
                        hasBareLoader = true;
                        failures.add(file.getFileName() + ": " + line.trim());
                    }
                }
                if (hasBareLoader) {
                    failures.add("  -> localized fxml referenced: " + localizedFxml);
                }
            }
        }
        assertTrue(failures.isEmpty(), "FXMLLoader without a ResourceBundle is used for localized FXML (JavaFX would fail to open the page): " + failures);
    }
}
