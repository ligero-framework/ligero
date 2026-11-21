package com.ligero.middleware;

import com.ligero.http.BadRequestException;
import com.ligero.http.Context;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestHygieneMiddlewareTest {

    private final RequestHygieneMiddleware hygiene = new RequestHygieneMiddleware();

    private Context ctx(String uri) {
        return new Context(FakeRequest.of("GET", uri), new FakeResponse(), "/", null, null);
    }

    @Test
    void cleanRequestsPass() throws Exception {
        AtomicBoolean proceeded = new AtomicBoolean();
        hygiene.handle(ctx("/users/42?tag=a%20b"), () -> proceeded.set(true));
        assertThat(proceeded).isTrue();
    }

    @Test
    void rejectsNullBytesControlCharsAndEncodedTraversal() {
        String controlChar = "/a" + (char) 0x01 + "b";
        for (String bad : new String[] {"/a%00b", controlChar, "/x/%2e%2e/etc", "/x/.%2E/etc"}) {
            assertThatThrownBy(() -> hygiene.handle(ctx(bad), () -> { }))
                .as("uri %s", bad)
                .isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void singlePassCheckAgreesWithTheReadablePredicates() {
        String[] pieces = {"a", "..", ".", "%2e", "%2E", "/", "\\", "%5c", "%00", "?", "x.y", "%20", "b", "..."};
        java.util.Random random = new java.util.Random(42);
        for (int n = 0; n < 200_000; n++) {
            StringBuilder sb = new StringBuilder("/");
            int parts = 1 + random.nextInt(6);
            for (int i = 0; i < parts; i++) {
                sb.append(pieces[random.nextInt(pieces.length)]);
            }
            String uri = sb.toString();
            boolean expected = RequestHygieneMiddleware.hasControlCharacters(uri)
                || RequestHygieneMiddleware.hasEncodedTraversal(uri)
                || RequestHygieneMiddleware.hasTraversalSegment(uri);
            assertThat(RequestHygieneMiddleware.isMalformed(uri)).as(uri).isEqualTo(expected);
        }
    }
}
