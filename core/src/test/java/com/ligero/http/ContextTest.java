package com.ligero.http;

import com.ligero.spi.BodyMapper;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextTest {

    @Test
    void stripsQueryAndContextPathFromPath() {
        Context ctx = new Context(FakeRequest.of("GET", "/api/users?x=1"),
            new FakeResponse(), "/api", null, null);
        assertThat(ctx.path()).isEqualTo("/users");
    }

    @Test
    void pathParamConversionFailsWith400Semantics() {
        Context ctx = new Context(FakeRequest.of("GET", "/"), new FakeResponse(), "/", null, null);
        ctx.pathParams().put("id", "abc");

        assertThat(ctx.pathParam("id")).isEqualTo("abc");
        assertThatThrownBy(() -> ctx.pathParamAsInt("id"))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ctx.pathParamAsLong("missing"))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void legacyRequestViewExposesPathParams() {
        Context ctx = new Context(FakeRequest.of("GET", "/users/9"), new FakeResponse(), "/", null, null);
        ctx.pathParams().put("id", "9");

        assertThat(ctx.req().getPathParams()).containsEntry("id", "9");
    }

    @Test
    void bodyWithoutMapperFailsWithClearMessage() {
        Context ctx = new Context(FakeRequest.of("POST", "/").body("{}"),
            new FakeResponse(), "/", null, null);
        assertThatThrownBy(() -> ctx.body(Object.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ligero-json");
    }

    @Test
    void bodyUsesConfiguredMapper() {
        BodyMapper upper = new BodyMapper() {
            @Override
            public String writeJson(Object value) {
                return String.valueOf(value);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T readJson(String json, Class<T> type) {
                return (T) json.toUpperCase();
            }
        };
        Context ctx = new Context(FakeRequest.of("POST", "/").body("abc"),
            new FakeResponse(), "/", upper, null);
        assertThat(ctx.body(String.class)).isEqualTo("ABC");
    }

    @Test
    void parsesFormParams() {
        FakeRequest request = FakeRequest.of("POST", "/register")
            .body("name=Ada+Lovelace&tag=a&tag=b&empty=");
        Context ctx = new Context(request, new FakeResponse(), "/", null, null);

        assertThat(ctx.formParam("name")).isEqualTo("Ada Lovelace");
        assertThat(ctx.formParams().get("tag")).containsExactly("a", "b");
        assertThat(ctx.formParam("empty")).isEmpty();
    }

    @Test
    void parsesCookiesAndSetsThem() {
        FakeRequest request = FakeRequest.of("GET", "/").header("Cookie", "a=1; b=2");
        FakeResponse response = new FakeResponse();
        Context ctx = new Context(request, response, "/", null, null);

        assertThat(ctx.cookie("a")).isEqualTo("1");
        assertThat(ctx.cookie("b")).isEqualTo("2");

        ctx.setCookie(Cookie.of("session", "xyz").withSecure(true));
        assertThat(response.headerValue("Set-Cookie"))
            .contains("session=xyz").contains("Secure").contains("HttpOnly").contains("SameSite=Lax");
    }

    @Test
    void attributesRoundTrip() {
        Context ctx = new Context(FakeRequest.of("GET", "/"), new FakeResponse(), "/", null, null);
        ctx.attribute("user", "ada");
        assertThat(ctx.<String>attribute("user")).isEqualTo("ada");
        assertThat(ctx.<String>attribute("missing")).isNull();
    }

    @Test
    void renderWithoutEngineFails() {
        Context ctx = new Context(FakeRequest.of("GET", "/"), new FakeResponse(), "/", null, null);
        assertThatThrownBy(() -> ctx.render("view", java.util.Map.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TemplateEngine");
    }

    @Test
    void responseHelpersSetContentType() {
        FakeResponse response = new FakeResponse();
        Context ctx = new Context(FakeRequest.of("GET", "/"), response, "/", null, null);
        ctx.html("<p>hi</p>");
        assertThat(response.contentTypeValue()).isEqualTo("text/html; charset=utf-8");
        assertThat(response.body()).isEqualTo("<p>hi</p>");
    }
}
