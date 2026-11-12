package com.ligero;

import com.ligero.http.BadRequestException;
import com.ligero.http.Context;
import com.ligero.http.Cookie;
import com.ligero.http.Multipart;
import com.ligero.middleware.RequestHygieneMiddleware;
import com.ligero.middleware.RequestIdMiddleware;
import com.ligero.middleware.TokenBucketStore;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regression tests for the hardening of request parsing and response headers. */
class SecurityHardeningTest {

    // ---- cookies -----------------------------------------------------------

    @Test
    void cookieValueCannotSmuggleAttributesOrHeaders() {
        assertThatThrownBy(() -> Cookie.of("theme", "dark; Domain=evil.example"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cookie.of("theme", "dark\r\nSet-Cookie: admin=1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cookie.of("bad name", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cookie.of("a=b", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cookie.of("ok", "x").withPath("/; HttpOnly"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameSiteNoneImpliesSecure() {
        assertThat(Cookie.of("sid", "abc").withSameSite("None").toSetCookieHeader())
            .contains("SameSite=None").contains("Secure");
    }

    @Test
    void removeCookieRejectsInjectedNames() {
        Context ctx = new Context(FakeRequest.of("GET", "/"), new FakeResponse(), "/", null, null);
        assertThatThrownBy(() -> ctx.removeCookie("sid; Domain=evil.example"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- request hygiene ---------------------------------------------------

    @Test
    void literalAndEncodedDotSegmentsAreRejected() throws Exception {
        RequestHygieneMiddleware hygiene = new RequestHygieneMiddleware();
        for (String bad : new String[] {
            "/files/../secret", "/files/..", "/a/./b", "/files/%2E%2E/secret", "/files/.%2e/x",
            "/files/..\\secret", "/files/%5c..%5csecret"}) {
            Context ctx = new Context(FakeRequest.of("GET", bad), new FakeResponse(), "/", null, null);
            assertThatThrownBy(() -> hygiene.handle(ctx, () -> { }))
                .as(bad).isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void ordinaryPathsWithDotsStillPass() throws Exception {
        RequestHygieneMiddleware hygiene = new RequestHygieneMiddleware();
        for (String ok : new String[] {"/files/report.v2.pdf", "/a/..hidden", "/a/b...c", "/x?path=../y"}) {
            AtomicBoolean proceeded = new AtomicBoolean();
            Context ctx = new Context(FakeRequest.of("GET", ok), new FakeResponse(), "/", null, null);
            hygiene.handle(ctx, () -> proceeded.set(true));
            assertThat(proceeded).as(ok).isTrue();
        }
    }

    // ---- request id --------------------------------------------------------

    @Test
    void unsafeIncomingRequestIdIsReplaced() throws Exception {
        FakeResponse response = new FakeResponse();
        FakeRequest request = FakeRequest.of("GET", "/")
            .header("X-Request-Id", "abc\",\"admin\":true,\"x\":\"");
        Context ctx = new Context(request, response, "/", null, null);

        new RequestIdMiddleware().handle(ctx, () -> { });

        String id = ctx.attribute(RequestIdMiddleware.ATTRIBUTE);
        assertThat(id).doesNotContain("\"").matches("[A-Za-z0-9-]+");
        assertThat(response.headerValue("X-Request-Id")).isEqualTo(id);
    }

    @Test
    void oversizedRequestIdIsReplaced() throws Exception {
        FakeRequest request = FakeRequest.of("GET", "/").header("X-Request-Id", "a".repeat(500));
        Context ctx = new Context(request, new FakeResponse(), "/", null, null);
        new RequestIdMiddleware().handle(ctx, () -> { });
        assertThat(ctx.<String>attribute(RequestIdMiddleware.ATTRIBUTE)).hasSizeLessThan(100);
    }

    // ---- rate limiter ------------------------------------------------------

    @Test
    void rateLimiterNeverTracksMoreThanItsKeyBudget() {
        TokenBucketStore store = new TokenBucketStore(5, 0.001, 100);
        for (int i = 0; i < 10_000; i++) {
            store.tryAcquire("client-" + i);
        }
        assertThat(store.size()).isLessThanOrEqualTo(100);
        // saturated with live buckets: unknown keys are refused, known ones still served
        assertThat(store.tryAcquire("brand-new")).isFalse();
        assertThat(store.tryAcquire("client-0")).isTrue();
    }

    @Test
    void idleBucketsAreReclaimed() throws Exception {
        // refills fully in 1 ms, so every bucket is reclaimable almost immediately
        TokenBucketStore store = new TokenBucketStore(1, 1_000, 10);
        for (int i = 0; i < 10; i++) {
            store.tryAcquire("k" + i);
        }
        Thread.sleep(20);
        assertThat(store.tryAcquire("fresh")).isTrue();
        assertThat(store.size()).isLessThanOrEqualTo(10);
    }

    // ---- form and body parsing ----------------------------------------------

    @Test
    void malformedFormEncodingIsABadRequest() {
        FakeRequest request = FakeRequest.of("POST", "/").body("name=%zz")
            .header("Content-Type", "application/x-www-form-urlencoded");
        Context ctx = new Context(request, new FakeResponse(), "/", null, null);
        assertThatThrownBy(() -> ctx.formParam("name")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void uploadedFilenameIsReducedToABareName() {
        assertThat(new Multipart.UploadedFile("f", "../../etc/passwd", null, new byte[0]).safeFilename())
            .isEqualTo("passwd");
        assertThat(new Multipart.UploadedFile("f", "C:\\Users\\x\\evil.exe", null, new byte[0]).safeFilename())
            .isEqualTo("evil.exe");
        assertThat(new Multipart.UploadedFile("f", "..", null, new byte[0]).safeFilename()).isEqualTo("upload");
        assertThat(new Multipart.UploadedFile("f", null, null, new byte[0]).safeFilename()).isEqualTo("upload");
        assertThat(new Multipart.UploadedFile("f", "photo.png", null, new byte[0]).safeFilename())
            .isEqualTo("photo.png");
    }

    @Test
    void tooManyMultipartPartsAreRejected() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i <= Multipart.MAX_PARTS; i++) {
            body.append("--b\r\nContent-Disposition: form-data; name=\"f").append(i).append("\"\r\n\r\nv\r\n");
        }
        body.append("--b--\r\n");
        byte[] bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> Multipart.parse(bytes, "multipart/form-data; boundary=b"))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("Too many");
    }
}
