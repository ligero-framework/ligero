package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.http.Handler;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MiddlewarePipelineTest {

    private static Context context(String method, String uri) {
        return new Context(FakeRequest.of(method, uri), new FakeResponse(), "/", null, null);
    }

    @Test
    void runsMiddlewaresInRegistrationOrderAroundTerminal() throws Exception {
        List<String> order = new ArrayList<>();
        Middleware first = (ctx, chain) -> {
            order.add("first-in");
            chain.proceed();
            order.add("first-out");
        };
        Middleware second = (ctx, chain) -> {
            order.add("second-in");
            chain.proceed();
            order.add("second-out");
        };
        Handler terminal = ctx -> order.add("terminal");

        MiddlewarePipeline.compose(List.of(first, second), terminal).handle(context("GET", "/"));

        assertThat(order).containsExactly("first-in", "second-in", "terminal", "second-out", "first-out");
    }

    @Test
    void notCallingProceedShortCircuits() throws Exception {
        List<String> order = new ArrayList<>();
        Middleware gate = (ctx, chain) -> order.add("gate");
        Handler terminal = ctx -> order.add("terminal");

        MiddlewarePipeline.compose(List.of(gate), terminal).handle(context("GET", "/"));

        assertThat(order).containsExactly("gate");
    }

    @Test
    void scopedMiddlewareOnlyAppliesUnderPrefix() throws Exception {
        List<String> hits = new ArrayList<>();
        Middleware scoped = MiddlewarePipeline.scoped("/api", (ctx, chain) -> {
            hits.add(ctx.path());
            chain.proceed();
        });
        Handler terminal = ctx -> { };

        MiddlewarePipeline.compose(List.of(scoped), terminal).handle(context("GET", "/api/users"));
        MiddlewarePipeline.compose(List.of(scoped), terminal).handle(context("GET", "/api"));
        MiddlewarePipeline.compose(List.of(scoped), terminal).handle(context("GET", "/apiary"));
        MiddlewarePipeline.compose(List.of(scoped), terminal).handle(context("GET", "/other"));

        assertThat(hits).containsExactly("/api/users", "/api");
    }
}
