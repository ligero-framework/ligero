package com.ligero.http;

import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AcceptsTest {

    private static Context withAccept(String accept) {
        FakeRequest request = FakeRequest.of("GET", "/");
        if (accept != null) {
            request.header("Accept", accept);
        }
        return new Context(request, new FakeResponse(), "/", null, null);
    }

    @Test
    void missingAcceptHeaderAcceptsEverything() {
        assertThat(withAccept(null).accepts("application/json")).isTrue();
    }

    @Test
    void exactAndWildcardMatching() {
        Context ctx = withAccept("text/html, application/json");
        assertThat(ctx.accepts("application/json")).isTrue();
        assertThat(ctx.accepts("image/png")).isFalse();
        assertThat(withAccept("image/*").accepts("image/png")).isTrue();
    }

    @Test
    void preferredHonorsQValues() {
        Context ctx = withAccept("text/html;q=0.5, application/json;q=0.9, */*;q=0.1");
        assertThat(ctx.preferredType(List.of("text/html", "application/json")))
            .isEqualTo("application/json");
        assertThat(ctx.preferredType(List.of("image/png"))).isEqualTo("image/png"); // via */*
        assertThat(withAccept("text/html").preferredType(List.of("image/png"))).isNull();
    }
}
