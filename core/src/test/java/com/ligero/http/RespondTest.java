package com.ligero.http;

import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code ctx.respond(...)}: how a typed controller's return value becomes a response. */
class RespondTest {

    private final FakeResponse response = new FakeResponse();
    private final Context ctx = new Context(FakeRequest.of("GET", "/"), response, "/", null, null);

    @Test
    void objectsBecomeJson() {
        ctx.respond(Map.of("a", 1));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.contentTypeValue()).isEqualTo("application/json");
        assertThat(response.body()).isEqualTo("{a=1}");
    }

    @Test
    void stringsBecomeText() {
        ctx.respond("hello");
        assertThat(response.contentTypeValue()).startsWith("text/plain");
        assertThat(response.body()).isEqualTo("hello");
    }

    @Test
    void nullBecomes204() {
        ctx.respond(null);
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.isCommitted()).isTrue();
    }

    @Test
    void nullKeepsAnExplicitStatus() {
        ctx.status(202).respond(null);
        assertThat(response.getStatus()).isEqualTo(202);
    }

    @Test
    void emptyOptionalIs404AndPresentOptionalIsUnwrapped() {
        assertThatThrownBy(() -> ctx.respond(Optional.empty())).isInstanceOf(NotFoundException.class);
        ctx.respond(Optional.of("found"));
        assertThat(response.body()).isEqualTo("found");
    }

    @Test
    void typedResponseSetsStatusAndHeaders() {
        ctx.respond(Response.created("/things/7", Map.of("id", 7)));
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.headerValue("Location")).isEqualTo("/things/7");
        assertThat(response.body()).isEqualTo("{id=7}");
    }

    @Test
    void noContentResponse() {
        ctx.respond(Response.noContent());
        assertThat(response.getStatus()).isEqualTo(204);
    }

    @Test
    void alreadyCommittedResponsesAreLeftAlone() {
        ctx.text("written by the handler");
        ctx.respond(Map.of("ignored", true));
        assertThat(response.body()).isEqualTo("written by the handler");
    }

    @Test
    void responseIsImmutableAndValidatesStatus() {
        Response<String> base = Response.ok("x");
        Response<String> withHeader = base.header("X-A", "1");
        assertThat(base.headers()).isEmpty();
        assertThat(withHeader.headers()).containsEntry("X-A", "1");
        assertThatThrownBy(() -> Response.status(99)).isInstanceOf(IllegalArgumentException.class);
    }
}
