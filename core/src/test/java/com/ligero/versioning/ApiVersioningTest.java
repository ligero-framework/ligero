package com.ligero.versioning;

import com.ligero.http.Context;
import com.ligero.http.Handler;
import com.ligero.http.ProblemException;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiVersioningTest {

    private record Exchange(Context ctx, FakeResponse response) {
    }

    private static Exchange run(ApiVersioning versioning, FakeRequest request, Handler handler) throws Exception {
        FakeResponse response = new FakeResponse();
        Context ctx = new Context(request, response, "/", null, null);
        versioning.handle(ctx, () -> handler.handle(ctx));
        return new Exchange(ctx, response);
    }

    private static Exchange run(ApiVersioning versioning, FakeRequest request) throws Exception {
        return run(versioning, request, ctx -> { });
    }

    @Test
    void parsesAndComparesVersions() {
        assertThat(ApiVersion.parse("2")).isEqualTo(ApiVersion.parse("v2.0")).isEqualTo(ApiVersion.parse("2.0.0"));
        assertThat(ApiVersion.parse("1.10")).isGreaterThan(ApiVersion.parse("1.9"));
        assertThat(ApiVersion.parse("1.0.3").toString()).isEqualTo("1.0.3");
        assertThat(ApiVersion.parse("V1.2").toString()).isEqualTo("1.2");
        assertThat(ApiVersion.parse("2").isAtLeast(ApiVersion.parse("1.5"))).isTrue();
        assertThat(ApiVersion.parse("3").major()).isEqualTo(3);
        assertThat(ApiVersion.parse("3.4.5").minor()).isEqualTo(4);
        assertThat(ApiVersion.parse("3.4.5").patch()).isEqualTo(5);
        assertThat(ApiVersion.parse("2").hashCode()).isEqualTo(ApiVersion.parse("2.0").hashCode());
        for (String bad : new String[] {"", "x", "1.", ".1", "1.2.3.4", "1a", "-1", "1234567890", "v", "9".repeat(40)}) {
            assertThat(ApiVersion.tryParse(bad)).as(bad).isNull();
        }
        assertThat(ApiVersion.tryParse(null)).isNull();
        assertThatThrownBy(() -> ApiVersion.parse("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsTheVersionFromAHeaderWithADefault() throws Exception {
        ApiVersioning versioning = ApiVersioning.builder().header("API-Version")
            .supported("1", "2").defaultVersion("2").build();

        Exchange explicit = run(versioning, FakeRequest.of("GET", "/x").header("API-Version", "1"));
        assertThat(explicit.ctx().apiVersion()).isEqualTo(ApiVersion.parse("1"));
        assertThat(explicit.response().headerValue("Vary")).isEqualTo("API-Version");

        assertThat(run(versioning, FakeRequest.of("GET", "/x")).ctx().apiVersion()).isEqualTo(ApiVersion.parse("2"));
    }

    @Test
    void unsupportedMalformedAndMissingVersionsAreProblems() {
        ApiVersioning versioning = ApiVersioning.builder().header("API-Version").supported("1", "2").required().build();

        assertThatThrownBy(() -> run(versioning, FakeRequest.of("GET", "/x").header("API-Version", "3")))
            .isInstanceOfSatisfying(ProblemException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(400);
                assertThat(e.toProblem().title()).isEqualTo("Unsupported API version");
                assertThat(e.toProblem().toJson()).contains("\"supported\":[\"1\",\"2\"]");
            });
        assertThatThrownBy(() -> run(versioning, FakeRequest.of("GET", "/x").header("API-Version", "<script>")))
            .isInstanceOfSatisfying(ProblemException.class, e -> {
                assertThat(e.toProblem().title()).isEqualTo("Malformed API version");
                assertThat(e.toProblem().toJson()).doesNotContain("script");
            });
        assertThatThrownBy(() -> run(versioning, FakeRequest.of("GET", "/x")))
            .isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.toProblem().title()).isEqualTo("API version required"));
    }

    @Test
    void withoutDefaultOrRequirementTheVersionIsOptional() throws Exception {
        ApiVersioning versioning = ApiVersioning.builder().queryParam("version").supported("1").build();
        assertThat(run(versioning, FakeRequest.of("GET", "/x")).ctx().apiVersion()).isNull();
        assertThat(run(versioning, FakeRequest.of("GET", "/x").queryParam("version", "1")).ctx().apiVersion())
            .isEqualTo(ApiVersion.parse("1"));
    }

    @Test
    void readsPathSegmentsAndMediaTypeParameters() throws Exception {
        ApiVersioning path = ApiVersioning.builder().pathSegment(0).supported("1", "2").defaultVersion("1").build();
        assertThat(run(path, FakeRequest.of("GET", "/v2/products")).ctx().apiVersion()).isEqualTo(ApiVersion.parse("2"));
        // a segment that is not a version is not one
        assertThat(run(path, FakeRequest.of("GET", "/health")).ctx().apiVersion()).isEqualTo(ApiVersion.parse("1"));
        assertThat(run(path, FakeRequest.of("GET", "/")).ctx().apiVersion()).isEqualTo(ApiVersion.parse("1"));

        ApiVersioning media = ApiVersioning.builder().mediaTypeParameter("version").supported("1", "2").build();
        Exchange accept = run(media, FakeRequest.of("GET", "/x")
            .header("Accept", "text/html, application/vnd.shop+json; version=\"2\""));
        assertThat(accept.ctx().apiVersion()).isEqualTo(ApiVersion.parse("2"));
        assertThat(accept.response().headerValue("Vary")).isEqualTo("Accept");
        assertThat(run(media, FakeRequest.of("POST", "/x").header("Content-Type", "application/json;version=1"))
            .ctx().apiVersion()).isEqualTo(ApiVersion.parse("1"));
        assertThat(run(media, FakeRequest.of("GET", "/x").header("Accept", "application/json")).ctx().apiVersion())
            .isNull();
    }

    @Test
    void theFirstSourceThatCarriesAVersionWins() throws Exception {
        ApiVersioning versioning = ApiVersioning.builder().header("API-Version").queryParam("v")
            .supported("1", "2").build();
        assertThat(run(versioning, FakeRequest.of("GET", "/x").queryParam("v", "2")).ctx().apiVersion())
            .isEqualTo(ApiVersion.parse("2"));
        assertThat(run(versioning, FakeRequest.of("GET", "/x").header("API-Version", "1").queryParam("v", "2"))
            .ctx().apiVersion()).isEqualTo(ApiVersion.parse("1"));
    }

    @Test
    void deprecatedVersionsAnnounceTheirSunset() throws Exception {
        ApiVersioning versioning = ApiVersioning.builder().header("API-Version").supported("1", "2")
            .deprecate("1", ApiVersioning.Deprecation.since(Instant.parse("2026-06-01T00:00:00Z"))
                .sunset(Instant.parse("2027-01-01T00:00:00Z"))
                .link("https://example.com/migrate"))
            .build();

        FakeResponse old = run(versioning, FakeRequest.of("GET", "/x").header("API-Version", "1")).response();
        assertThat(old.headerValue("Deprecation")).isEqualTo("@1780272000");
        assertThat(old.headerValue("Sunset")).isEqualTo("Fri, 1 Jan 2027 00:00:00 GMT");
        assertThat(old.headerValue("Link")).isEqualTo("<https://example.com/migrate>; rel=\"deprecation\"; type=\"text/html\"");

        FakeResponse current = run(versioning, FakeRequest.of("GET", "/x").header("API-Version", "2")).response();
        assertThat(current.headerValue("Deprecation")).isNull();
    }

    @Test
    void handlersDispatchOnTheVersion() throws Exception {
        ApiVersioning versioning = ApiVersioning.builder().header("API-Version")
            .supported("1", "1.1", "2", "3", "4").build();
        Handler route = ApiVersioning.handlers()
            .version("1", ctx -> ctx.text("v1"))
            .version("1.1+", ctx -> ctx.text("v1.1+"))
            .version("3+", ctx -> ctx.text("v3+"))
            .version("3", ctx -> ctx.text("exactly v3"))
            .build();

        assertThat(run(versioning, FakeRequest.of("GET", "/x").header("API-Version", "1"), route).response().body())
            .isEqualTo("v1");
        assertThat(run(versioning, FakeRequest.of("GET", "/x").header("API-Version", "2"), route).response().body())
            .isEqualTo("v1.1+");
        assertThat(run(versioning, FakeRequest.of("GET", "/x").header("API-Version", "3"), route).response().body())
            .isEqualTo("exactly v3");
        assertThat(run(versioning, FakeRequest.of("GET", "/x").header("API-Version", "4"), route).response().body())
            .isEqualTo("v3+");
        assertThatThrownBy(() -> run(versioning, FakeRequest.of("GET", "/x"), route))
            .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void aVersionWithoutHandlerIs404UnlessThereIsAFallback() throws Exception {
        ApiVersioning versioning = ApiVersioning.builder().header("API-Version").supported("1", "2").build();
        Handler onlyV2 = ApiVersioning.handlers().version("2", ctx -> ctx.text("v2")).build();
        assertThatThrownBy(() -> run(versioning, FakeRequest.of("GET", "/x").header("API-Version", "1"), onlyV2))
            .isInstanceOfSatisfying(ProblemException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(404);
                assertThat(e.getMessage()).isEqualTo("GET /x is not available in API version 1");
            });

        Handler withFallback = ApiVersioning.handlers().version("2", ctx -> ctx.text("v2"))
            .otherwise(ctx -> ctx.text("fallback")).build();
        assertThat(run(versioning, FakeRequest.of("GET", "/x").header("API-Version", "1"), withFallback)
            .response().body()).isEqualTo("fallback");
        assertThat(run(versioning, FakeRequest.of("GET", "/x"), withFallback).response().body()).isEqualTo("fallback");
    }

    @Test
    void misconfigurationFailsAtStartup() {
        assertThatThrownBy(() -> ApiVersioning.builder().supported("1").build())
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ApiVersioning.builder().header("V").build())
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ApiVersioning.builder().header("V").supported("1").defaultVersion("2").build())
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ApiVersioning.builder().header("V").supported("1")
            .deprecate("2", ApiVersioning.Deprecation.since(Instant.EPOCH)).build())
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ApiVersioning.builder().pathSegment(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiVersioning.Deprecation.since(Instant.EPOCH).link("javascript:alert(1)"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiVersioning.handlers().build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ApiVersioning.handlers().version("2", ctx -> { }).version("v2.0", ctx -> { }))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void varyMergesWithOtherMiddleware() throws Exception {
        FakeResponse response = new FakeResponse();
        Context ctx = new Context(FakeRequest.of("GET", "/x").header("API-Version", "1"), response, "/", null, null);
        ctx.vary("Accept-Language").vary("accept-language");
        ApiVersioning.builder().header("API-Version").supported("1").build().handle(ctx, () -> { });
        assertThat(response.headerValues("Vary")).containsExactly("Accept-Language", "API-Version");
    }
}
