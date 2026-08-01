package com.sqlteacher.application.update;

import java.util.Objects;
import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict SemVer 2.0 value used for update decisions; never compare versions as decimals or strings. */
public record SemanticVersion(int major, int minor, int patch, String prerelease) implements Comparable<SemanticVersion> {
    private static final Pattern FORMAT = Pattern.compile(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
    );

    public SemanticVersion {
        if (major < 0 || minor < 0 || patch < 0) throw new IllegalArgumentException("version parts must be non-negative");
        prerelease = prerelease == null ? "" : prerelease;
    }

    public static SemanticVersion parse(String value) {
        if (value == null || value.length() > 128) throw new IllegalArgumentException("version is invalid");
        Matcher matcher = FORMAT.matcher(value.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("version is invalid");
        try {
            return new SemanticVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)), Objects.requireNonNullElse(matcher.group(4), ""));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("version is outside supported range", error);
        }
    }

    public boolean stable() { return prerelease.isEmpty(); }

    @Override public int compareTo(SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        if (result == 0) result = Integer.compare(patch, other.patch);
        if (result != 0) return result;
        if (prerelease.isEmpty()) return other.prerelease.isEmpty() ? 0 : 1;
        if (other.prerelease.isEmpty()) return -1;
        String[] left = prerelease.split("\\.");
        String[] right = other.prerelease.split("\\.");
        for (int index = 0; index < Math.min(left.length, right.length); index++) {
            boolean leftNumber = left[index].matches("0|[1-9]\\d*");
            boolean rightNumber = right[index].matches("0|[1-9]\\d*");
            if (leftNumber && rightNumber) result = new BigInteger(left[index]).compareTo(new BigInteger(right[index]));
            else if (leftNumber != rightNumber) result = leftNumber ? -1 : 1;
            else result = left[index].compareTo(right[index]);
            if (result != 0) return result;
        }
        return Integer.compare(left.length, right.length);
    }

    @Override public String toString() {
        return major + "." + minor + "." + patch + (prerelease.isEmpty() ? "" : "-" + prerelease);
    }
}
