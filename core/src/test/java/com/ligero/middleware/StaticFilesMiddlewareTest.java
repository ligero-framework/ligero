package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class StaticFilesMiddlewareTest {

    @TempDir
    Path root;

    private StaticFilesMiddleware middleware;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(root.resolve("site.css"), "body { color: red; }");
        Files.createDirectory(root.resolve("sub"));
        Files.writeString(root.resolve("sub/page.html"), "<h1>hi</h1>");
        Files.writeString(root.resolve("index.html"), "<h1>index</h1>");
        middleware = StaticFilesMiddleware.external("/static", root);
    }

    private static Context context(String method, String uri) {
        return new Context(FakeRequest.of(method, uri), new FakeResponse(), "/", null, null);
    }

    @Test
    void servesFileWithContentType() throws Exception {
        Context ctx = context("GET", "/static/site.css");

        middleware.handle(ctx, () -> { throw new AssertionError("chain must not run"); });

        FakeResponse response = (FakeResponse) ctx.res();
        assertThat(response.body()).contains("color: red");
        assertThat(response.contentTypeValue()).isEqualTo("text/css; charset=utf-8");
        assertThat(response.headerValue("Cache-Control")).contains("max-age");
        assertThat(response.headerValue("ETag")).isNotNull();
    }

    @Test
    void servesNestedFilesAndIndex() throws Exception {
        Context nested = context("GET", "/static/sub/page.html");
        middleware.handle(nested, () -> { });
        assertThat(((FakeResponse) nested.res()).body()).contains("hi");

        Context index = context("GET", "/static");
        middleware.handle(index, () -> { });
        assertThat(((FakeResponse) index.res()).body()).contains("index");
    }

    @Test
    void returns304WhenEtagMatches() throws Exception {
        Context first = context("GET", "/static/site.css");
        middleware.handle(first, () -> { });
        String etag = ((FakeResponse) first.res()).headerValue("ETag");

        FakeResponse response = new FakeResponse();
        FakeRequest request = FakeRequest.of("GET", "/static/site.css").header("If-None-Match", etag);
        middleware.handle(new Context(request, response, "/", null, null), () -> { });

        assertThat(response.getStatus()).isEqualTo(304);
        assertThat(response.body()).isNull();
    }

    @Test
    void blocksPathTraversal() throws Exception {
        for (String attack : new String[] {
                "/static/../secret.txt",
                "/static/sub/../../secret.txt",
                "/static/..",
                "/static/a\\..\\b"}) {
            AtomicBoolean fellThrough = new AtomicBoolean();
            Context ctx = context("GET", attack);
            middleware.handle(ctx, () -> fellThrough.set(true));
            assertThat(fellThrough).as("must not serve %s", attack).isTrue();
            assertThat(ctx.res().isCommitted()).isFalse();
        }
    }

    @Test
    void fallsThroughForMissingFilesAndOtherPrefixes() throws Exception {
        AtomicBoolean missing = new AtomicBoolean();
        middleware.handle(context("GET", "/static/nope.css"), () -> missing.set(true));
        assertThat(missing).isTrue();

        AtomicBoolean other = new AtomicBoolean();
        middleware.handle(context("GET", "/api/users"), () -> other.set(true));
        assertThat(other).isTrue();
    }

    @Test
    void ignoresNonGetMethods() throws Exception {
        AtomicBoolean fellThrough = new AtomicBoolean();
        middleware.handle(context("POST", "/static/site.css"), () -> fellThrough.set(true));
        assertThat(fellThrough).isTrue();
    }

    @Test
    void servesFromClasspath() throws Exception {
        StaticFilesMiddleware cp = StaticFilesMiddleware.classpath("/assets", "static-test");
        Context ctx = context("GET", "/assets/hello.txt");

        cp.handle(ctx, () -> { throw new AssertionError("chain must not run"); });

        assertThat(((FakeResponse) ctx.res()).body()).contains("hello from classpath");
    }
}
