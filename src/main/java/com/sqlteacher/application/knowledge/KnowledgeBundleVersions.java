package com.sqlteacher.application.knowledge;

/**
 * Dot-separated integer version comparison for knowledge bundles (v3.4.3 OKB-5/OKB-4).
 * Versions look like {@code 1.0.0}; non-numeric segments compare as 0. Missing trailing
 * segments are treated as 0, so {@code 1.2} equals {@code 1.2.0}.
 */
public final class KnowledgeBundleVersions {

    private KnowledgeBundleVersions() {
    }

    /** True when {@code candidate} is strictly newer than {@code current}. A blank current means "nothing installed". */
    public static boolean isNewer(String candidate, String current) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (current == null || current.isBlank()) {
            return true;
        }
        int[] left = parse(candidate);
        int[] right = parse(current);
        int length = Math.max(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int a = index < left.length ? left[index] : 0;
            int b = index < right.length ? right[index] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    private static int[] parse(String version) {
        String[] parts = version.trim().split("\\.");
        int[] values = new int[parts.length];
        for (int index = 0; index < parts.length; index++) {
            String digits = parts[index].replaceAll("[^0-9].*$", "");
            int value = 0;
            if (!digits.isEmpty()) {
                try {
                    value = Integer.parseInt(digits);
                } catch (NumberFormatException ignored) {
                    value = 0;
                }
            }
            values[index] = value;
        }
        return values;
    }
}
