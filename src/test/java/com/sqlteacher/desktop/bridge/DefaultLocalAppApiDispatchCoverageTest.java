package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.4.0 REF-8: registry dispatch coverage. Every method allowed by the frozen {@code 3.0-v1}
 * contract (mirrored by the Rust ALLOWED_METHODS via {@code contracts/ipc/v1/manifest.json})
 * must have exactly one section handler, and no section may claim anything else.
 */
class DefaultLocalAppApiDispatchCoverageTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void everyWhitelistedMethodMustHaveExactlyOneSectionHandler() {
        try (var api = new DefaultLocalAppApi(mapper)) {
            // Registry covers the full Java whitelist, nothing more.
            assertEquals(LocalAppContract.API_METHODS, api.dispatchedMethods());

            // The union of the section slices is exactly the whitelist: nothing missing, nothing extra.
            Set<String> union = new HashSet<>();
            for (LocalAppApiSection section : allSections(api)) {
                assertTrue(!section.supportedMethods().isEmpty(), () ->
                    section.getClass().getSimpleName() + " must own at least one method");
                for (String method : section.supportedMethods()) {
                    assertTrue(union.add(method), () -> "Method registered by two sections: " + method);
                }
            }
            assertEquals(LocalAppContract.API_METHODS, union);
        }
    }

    @Test
    void machineReadableManifestMethodsMustAllBeDispatchable() throws Exception {
        var manifest = mapper.readTree(Path.of("contracts/ipc/v1/manifest.json").toFile());
        Set<String> manifestMethods = new HashSet<>();
        manifest.path("methods").forEach(item -> manifestMethods.add(item.asText()));

        Set<String> expected = new HashSet<>(LocalAppContract.API_METHODS);
        expected.addAll(LocalAppContract.RESERVED_METHODS);
        assertEquals(expected, manifestMethods, "manifest and Java contract drifted apart");

        try (var api = new DefaultLocalAppApi(mapper)) {
            for (String method : LocalAppContract.API_METHODS) {
                assertTrue(api.dispatchedMethods().contains(method),
                    () -> "ALLOWED method without a section handler: " + method);
            }
            // Reserved methods are handled by the protocol server, never by the API registry.
            for (String reserved : LocalAppContract.RESERVED_METHODS) {
                assertThrows(IllegalArgumentException.class,
                    () -> api.invoke(reserved, mapper.createObjectNode(), () -> false, ignored -> { }),
                    () -> "Reserved method must not be dispatchable: " + reserved);
            }
        }
    }

    /** Same slice list as the facade constructor; the facade fail-fast registry keeps it honest. */
    private List<LocalAppApiSection> allSections(DefaultLocalAppApi api) {
        ApiSectionHost host = api;
        return List.of(
            new SystemApiSection(host),
            new HomeApiSection(host),
            new KnowledgeApiSection(host),
            new ActivityApiSection(host),
            new PracticeApiSection(host),
            new DataSqlApiSection(host, new SqlConfirmationCache()),
            new AiApiSection(host),
            new AccountApiSection(host),
            new TeachingApiSection(host),
            new CloudApiSection(host),
            new SettingsApiSection(host)
        );
    }
}
