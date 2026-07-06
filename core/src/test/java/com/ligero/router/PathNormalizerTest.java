package com.ligero.router;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathNormalizerTest {

    @Test
    void nullAndEmptyBecomeRoot() {
        assertThat(PathNormalizer.normalize(null)).isEqualTo("/");
        assertThat(PathNormalizer.normalize("")).isEqualTo("/");
        assertThat(PathNormalizer.normalize("/")).isEqualTo("/");
    }

    @Test
    void ensuresLeadingSlash() {
        assertThat(PathNormalizer.normalize("users")).isEqualTo("/users");
    }

    @Test
    void collapsesDuplicateSlashes() {
        // regression for B4: the old regex missed double slashes
        assertThat(PathNormalizer.normalize("//users")).isEqualTo("/users");
        assertThat(PathNormalizer.normalize("/a//b///c")).isEqualTo("/a/b/c");
        assertThat(PathNormalizer.normalize("///")).isEqualTo("/");
    }

    @Test
    void stripsTrailingSlash() {
        assertThat(PathNormalizer.normalize("/users/")).isEqualTo("/users");
        assertThat(PathNormalizer.normalize("/a/b/")).isEqualTo("/a/b");
    }

    @Test
    void normalizesContextPath() {
        assertThat(PathNormalizer.normalizeContextPath(null)).isEqualTo("/");
        assertThat(PathNormalizer.normalizeContextPath("  ")).isEqualTo("/");
        assertThat(PathNormalizer.normalizeContextPath("api")).isEqualTo("/api");
        assertThat(PathNormalizer.normalizeContextPath("/api/")).isEqualTo("/api");
    }

    @Test
    void stripsContextPath() {
        assertThat(PathNormalizer.stripContextPath("/api/users", "/api")).isEqualTo("/users");
        assertThat(PathNormalizer.stripContextPath("/api", "/api")).isEqualTo("/");
        assertThat(PathNormalizer.stripContextPath("/apiary", "/api")).isEqualTo("/apiary");
        assertThat(PathNormalizer.stripContextPath("/users", "/")).isEqualTo("/users");
    }

    @Test
    void splitsSegments() {
        assertThat(PathNormalizer.segments("/")).isEmpty();
        assertThat(PathNormalizer.segments("/a/b")).containsExactly("a", "b");
    }
}
