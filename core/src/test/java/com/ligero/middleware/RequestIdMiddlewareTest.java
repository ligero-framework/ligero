package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdMiddlewareTest {

    @Test
    void reusesIncomingIdAndEchoesIt() throws Exception {
        FakeResponse response = new FakeResponse();
        FakeRequest request = FakeRequest.of("GET", "/").header("X-Request-Id", "req-1");
        Context ctx = new Context(request, response, "/", null, null);

        new RequestIdMiddleware().handle(ctx, () -> { });

        assertThat(ctx.<String>attribute(RequestIdMiddleware.ATTRIBUTE)).isEqualTo("req-1");
        assertThat(response.headerValue("X-Request-Id")).isEqualTo("req-1");
    }

    @Test
    void generatesIdWhenAbsent() throws Exception {
        Context ctx = new Context(FakeRequest.of("GET", "/"), new FakeResponse(), "/", null, null);
        new RequestIdMiddleware().handle(ctx, () -> { });
        assertThat(ctx.<String>attribute(RequestIdMiddleware.ATTRIBUTE)).isNotBlank();
    }

    @Test
    void propagatesW3cTraceId() throws Exception {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        FakeRequest request = FakeRequest.of("GET", "/").header("traceparent", traceparent);
        Context ctx = new Context(request, new FakeResponse(), "/", null, null);

        new RequestIdMiddleware().handle(ctx, () -> { });

        assertThat(ctx.<String>attribute(RequestIdMiddleware.TRACE_ID_ATTRIBUTE))
            .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(RequestIdMiddleware.parseTraceId("garbage")).isNull();
        assertThat(RequestIdMiddleware.parseTraceId("00-" + "0".repeat(32) + "-x-01")).isNull();
    }
}
