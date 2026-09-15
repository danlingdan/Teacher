package com.sqlteacher.application.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBundleVersionsTest {

    @Test
    void comparesDotSeparatedIntegers() {
        assertTrue(KnowledgeBundleVersions.isNewer("1.0.1", "1.0.0"));
        assertTrue(KnowledgeBundleVersions.isNewer("1.1.0", "1.0.9"));
        assertTrue(KnowledgeBundleVersions.isNewer("2.0.0", "1.9.9"));
        assertFalse(KnowledgeBundleVersions.isNewer("1.0.0", "1.0.0"));
        assertFalse(KnowledgeBundleVersions.isNewer("1.0.0", "1.0.1"));
        assertFalse(KnowledgeBundleVersions.isNewer("1.0", "1.0.0"));
    }

    @Test
    void treatsMissingCurrentAsNewerAndBlankCandidateAsNot() {
        assertTrue(KnowledgeBundleVersions.isNewer("1.0.0", null));
        assertTrue(KnowledgeBundleVersions.isNewer("1.0.0", ""));
        assertFalse(KnowledgeBundleVersions.isNewer(null, "1.0.0"));
        assertFalse(KnowledgeBundleVersions.isNewer("", "1.0.0"));
    }

    @Test
    void ignoresNonNumericSuffixes() {
        assertTrue(KnowledgeBundleVersions.isNewer("1.0.1-rc", "1.0.0"));
        assertFalse(KnowledgeBundleVersions.isNewer("1.0.0-beta", "1.0.0"));
    }
}
