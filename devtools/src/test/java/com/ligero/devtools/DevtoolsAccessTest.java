package com.ligero.devtools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The dashboard is loopback-only by default and never renders raw header values as ids. */
class DevtoolsAccessTest {

    @Test
    void recognizesLoopbackLiteralsOnly() {
        assertThat(Devtools.isLoopback("127.0.0.1")).isTrue();
        assertThat(Devtools.isLoopback("127.8.9.1")).isTrue();
        assertThat(Devtools.isLoopback("::1")).isTrue();
        assertThat(Devtools.isLoopback("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(Devtools.isLoopback("[::1]")).isTrue();

        assertThat(Devtools.isLoopback("192.168.1.20")).isFalse();
        assertThat(Devtools.isLoopback("10.0.0.5")).isFalse();
        assertThat(Devtools.isLoopback("203.0.113.9")).isFalse();
        assertThat(Devtools.isLoopback("localhost")).as("host names are never resolved").isFalse();
        assertThat(Devtools.isLoopback(null)).isFalse();
        assertThat(Devtools.isLoopback("")).isFalse();
    }

    @Test
    void traceIdsAreRestrictedToAnIdentifierAlphabet() {
        assertThat(TraceMiddleware.isSafeId("run-1a2b3c")).isTrue();
        assertThat(TraceMiddleware.isSafeId("9f1c2e7a-0000-4b6d-8a55-1f2e3d4c5b6a")).isTrue();

        assertThat(TraceMiddleware.isSafeId("');alert(document.domain)//")).isFalse();
        assertThat(TraceMiddleware.isSafeId("<img src=x onerror=alert(1)>")).isFalse();
        assertThat(TraceMiddleware.isSafeId("a".repeat(65))).isFalse();
        assertThat(TraceMiddleware.isSafeId("")).isFalse();
        assertThat(TraceMiddleware.isSafeId(null)).isFalse();
    }
}
