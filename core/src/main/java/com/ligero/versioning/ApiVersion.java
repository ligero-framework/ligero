package com.ligero.versioning;

import java.util.Objects;

/**
 * A request's API version: {@code major[.minor[.patch]]}, optionally written
 * with a leading {@code v} ({@code 2}, {@code v2}, {@code 1.1} and
 * {@code 2.0.0} are all valid). Versions compare numerically, and missing
 * parts count as zero, so {@code 2} equals {@code 2.0}.
 */
public final class ApiVersion implements Comparable<ApiVersion> {

    private static final int MAX_LENGTH = 32;

    private final int major;
    private final int minor;
    private final int patch;

    private ApiVersion(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    /**
     * Parses {@code text}.
     *
     * @throws IllegalArgumentException if it is not a version
     */
    public static ApiVersion parse(String text) {
        ApiVersion version = tryParse(text);
        if (version == null) {
            throw new IllegalArgumentException("Not an API version: " + text);
        }
        return version;
    }

    /** Parses {@code text}, or returns {@code null} if it is not a version. */
    public static ApiVersion tryParse(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            return null;
        }
        if (value.charAt(0) == 'v' || value.charAt(0) == 'V') {
            value = value.substring(1);
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length > 3) {
            return null;
        }
        int[] numbers = new int[3];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 9) {
                return null;
            }
            for (int c = 0; c < part.length(); c++) {
                if (part.charAt(c) < '0' || part.charAt(c) > '9') {
                    return null;
                }
            }
            numbers[i] = Integer.parseInt(part);
        }
        return new ApiVersion(numbers[0], numbers[1], numbers[2]);
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    /** True when this version is {@code other} or later. */
    public boolean isAtLeast(ApiVersion other) {
        return compareTo(other) >= 0;
    }

    @Override
    public int compareTo(ApiVersion other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        return byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ApiVersion other && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }

    /** The shortest form: {@code 2}, {@code 1.1}, {@code 1.0.3}. */
    @Override
    public String toString() {
        if (patch != 0) {
            return major + "." + minor + "." + patch;
        }
        return minor != 0 ? major + "." + minor : String.valueOf(major);
    }
}
