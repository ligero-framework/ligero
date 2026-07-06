package com.ligero.router;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteTrieTest {

    @Test
    void matchesStaticRoutes() {
        RouteTrie<String> trie = new RouteTrie<>();
        trie.insert("/users", "list");
        trie.insert("/users/active", "active");

        assertThat(trie.find("/users").value()).isEqualTo("list");
        assertThat(trie.find("/users/active").value()).isEqualTo("active");
        assertThat(trie.find("/missing")).isNull();
    }

    @Test
    void matchesRoot() {
        RouteTrie<String> trie = new RouteTrie<>();
        trie.insert("/", "root");
        assertThat(trie.find("/").value()).isEqualTo("root");
        assertThat(trie.find("/other")).isNull();
    }

    @Test
    void extractsPathParameters() {
        RouteTrie<String> trie = new RouteTrie<>();
        trie.insert("/users/{id}/posts/{postId}", "post");

        RouteTrie.Match<String> match = trie.find("/users/42/posts/7");
        assertThat(match.value()).isEqualTo("post");
        assertThat(match.params()).containsEntry("id", "42").containsEntry("postId", "7");
    }

    @Test
    void staticSegmentsWinOverParameters() {
        RouteTrie<String> trie = new RouteTrie<>();
        trie.insert("/users/{id}", "byId");
        trie.insert("/users/me", "me");

        assertThat(trie.find("/users/me").value()).isEqualTo("me");
        assertThat(trie.find("/users/1").value()).isEqualTo("byId");
    }

    @Test
    void backtracksWhenStaticBranchDeadEnds() {
        RouteTrie<String> trie = new RouteTrie<>();
        trie.insert("/a/b/c", "static");
        trie.insert("/a/{x}/d", "param");

        // "/a/b/d": the static child "b" exists but has no "d" below it,
        // so matching must back-track and try the parameter branch.
        RouteTrie.Match<String> match = trie.find("/a/b/d");
        assertThat(match.value()).isEqualTo("param");
        assertThat(match.params()).containsEntry("x", "b");
    }

    @Test
    void wildcardCapturesRemainder() {
        RouteTrie<String> trie = new RouteTrie<>();
        trie.insert("/files/*path", "files");

        RouteTrie.Match<String> match = trie.find("/files/css/site/main.css");
        assertThat(match.value()).isEqualTo("files");
        assertThat(match.params()).containsEntry("path", "css/site/main.css");
    }

    @Test
    void wildcardMustBeLastSegment() {
        RouteTrie<String> trie = new RouteTrie<>();
        assertThatThrownBy(() -> trie.insert("/files/*path/extra", "bad"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateRoutes() {
        RouteTrie<String> trie = new RouteTrie<>();
        trie.insert("/users/{id}", "first");
        assertThatThrownBy(() -> trie.insert("/users/{id}", "second"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already registered");
    }

    @Test
    void rejectsConflictingParameterNames() {
        RouteTrie<String> trie = new RouteTrie<>();
        trie.insert("/users/{id}", "byId");
        assertThatThrownBy(() -> trie.insert("/users/{name}/x", "byName"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Conflicting parameter names");
    }

    @Test
    void listsRegisteredPaths() {
        RouteTrie<String> trie = new RouteTrie<>();
        trie.insert("/a", "1");
        trie.insert("/a/{id}", "2");
        assertThat(trie.registeredPaths()).containsExactlyInAnyOrder("/a", "/a/{id}");
    }
}
