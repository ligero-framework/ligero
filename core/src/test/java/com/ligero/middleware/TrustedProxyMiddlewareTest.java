package com.ligero.middleware;

import com.ligero.http.Context;
import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedProxyMiddlewareTest {

    private static String clientSeen(TrustedProxyMiddleware middleware, String peer, String forwardedFor)
            throws Exception {
        FakeRequest request = FakeRequest.of("GET", "/").remoteAddress(peer);
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        Context ctx = new Context(request, new FakeResponse(), "/", null, null);
        String[] seen = new String[1];
        middleware.handle(ctx, () -> seen[0] = ctx.remoteAddress());
        return seen[0];
    }

    @Test
    void believesTheHeaderOnlyFromATrustedProxy() throws Exception {
        TrustedProxyMiddleware middleware = TrustedProxyMiddleware.of("10.0.0.0/8");

        assertThat(clientSeen(middleware, "10.1.2.3", "203.0.113.7")).isEqualTo("203.0.113.7");
        // a client connecting directly cannot choose its own address
        assertThat(clientSeen(middleware, "198.51.100.9", "203.0.113.7")).isEqualTo("198.51.100.9");
        // no header: the peer is all we know
        assertThat(clientSeen(middleware, "10.1.2.3", null)).isEqualTo("10.1.2.3");
    }

    @Test
    void readsTheChainFromTheRightAndIgnoresWhatTheClientPrepended() throws Exception {
        TrustedProxyMiddleware middleware = TrustedProxyMiddleware.of("10.0.0.0/8", "192.168.1.10");

        // client claimed to be 1.1.1.1; the edge proxy appended the address it really saw
        assertThat(clientSeen(middleware, "10.0.0.5", "1.1.1.1, 203.0.113.7, 192.168.1.10"))
            .isEqualTo("203.0.113.7");
        // every hop is ours: nothing to override
        assertThat(clientSeen(middleware, "10.0.0.5", "10.0.0.9, 192.168.1.10")).isEqualTo("10.0.0.5");
        // garbage stops the walk instead of being trusted
        assertThat(clientSeen(middleware, "10.0.0.5", "203.0.113.7, <script>")).isEqualTo("10.0.0.5");
    }

    @Test
    void supportsIpv6AndExactAddresses() throws Exception {
        TrustedProxyMiddleware middleware = TrustedProxyMiddleware.of("::1", "fd00::/8");

        assertThat(clientSeen(middleware, "0:0:0:0:0:0:0:1", "2001:db8::7")).isEqualTo("2001:db8::7");
        assertThat(clientSeen(middleware, "fd12:3456::1", "203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(clientSeen(middleware, "2001:db8::1", "203.0.113.7")).isEqualTo("2001:db8::1");
    }

    @Test
    void cidrBoundariesAreExact() throws Exception {
        TrustedProxyMiddleware middleware = TrustedProxyMiddleware.of("192.168.4.0/22");

        assertThat(clientSeen(middleware, "192.168.7.255", "203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(clientSeen(middleware, "192.168.8.0", "203.0.113.7")).isEqualTo("192.168.8.0");
        assertThat(clientSeen(middleware, "192.168.3.255", "203.0.113.7")).isEqualTo("192.168.3.255");
    }

    @Test
    void rejectsEntriesThatAreNotAddresses() {
        assertThatThrownBy(TrustedProxyMiddleware::of).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustedProxyMiddleware.of("proxy.internal"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustedProxyMiddleware.of("10.0.0.0/33"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static Context forwarded(TrustedProxyMiddleware middleware, String peer, String... forwarded)
            throws Exception {
        FakeRequest request = FakeRequest.of("GET", "/").remoteAddress(peer);
        for (String value : forwarded) {
            request.header("Forwarded", value);
        }
        Context ctx = new Context(request, new FakeResponse(), "/", null, null);
        middleware.handle(ctx, () -> { });
        return ctx;
    }

    @Test
    void readsTheStandardForwardedHeader() throws Exception {
        TrustedProxyMiddleware middleware = TrustedProxyMiddleware.of("10.0.0.0/8").withForwardedHeader();

        Context ctx = forwarded(middleware, "10.0.0.5",
            "for=1.1.1.1, for=203.0.113.7;proto=https;host=shop.example, for=10.0.0.9;proto=http");
        assertThat(ctx.remoteAddress()).isEqualTo("203.0.113.7");
        assertThat((String) ctx.attribute(TrustedProxyMiddleware.PROTO_ATTRIBUTE)).isEqualTo("https");
        assertThat((String) ctx.attribute(TrustedProxyMiddleware.HOST_ATTRIBUTE)).isEqualTo("shop.example");

        // quoted IPv6 with a port, IPv4 with a port, parameters in any case and order
        assertThat(forwarded(middleware, "10.0.0.5", "For=\"[2001:db8:cafe::17]:4711\"").remoteAddress())
            .isEqualTo("2001:db8:cafe::17");
        assertThat(forwarded(middleware, "10.0.0.5", "proto=http;for=\"198.51.100.4:8080\"").remoteAddress())
            .isEqualTo("198.51.100.4");
        // several header lines form one list
        assertThat(forwarded(middleware, "10.0.0.5", "for=203.0.113.7", "for=10.0.0.9").remoteAddress())
            .isEqualTo("203.0.113.7");
    }

    @Test
    void forwardedFromAnUntrustedPeerOrMalformedIsIgnored() throws Exception {
        TrustedProxyMiddleware middleware = TrustedProxyMiddleware.of("10.0.0.0/8").withForwardedHeader();

        assertThat(forwarded(middleware, "198.51.100.9", "for=203.0.113.7").remoteAddress())
            .isEqualTo("198.51.100.9");
        assertThat(forwarded(middleware, "10.0.0.5", "for=\"unterminated").remoteAddress()).isEqualTo("10.0.0.5");
        assertThat(forwarded(middleware, "10.0.0.5", "for=1.1.1.1;for=2.2.2.2").remoteAddress()).isEqualTo("10.0.0.5");
        assertThat(forwarded(middleware, "10.0.0.5", "garbage").remoteAddress()).isEqualTo("10.0.0.5");
        assertThat(forwarded(middleware, "10.0.0.5", "b@d=1").remoteAddress()).isEqualTo("10.0.0.5");
        assertThat(forwarded(middleware, "10.0.0.5", "for=[::1").remoteAddress()).isEqualTo("10.0.0.5");
        assertThat(forwarded(middleware, "10.0.0.5", "x".repeat(9000)).remoteAddress()).isEqualTo("10.0.0.5");
        // an obfuscated or unknown node stops the walk
        assertThat(forwarded(middleware, "10.0.0.5", "for=203.0.113.7, for=_hidden").remoteAddress())
            .isEqualTo("10.0.0.5");
        assertThat(forwarded(middleware, "10.0.0.5", "for=unknown").remoteAddress()).isEqualTo("10.0.0.5");
        // no header at all
        assertThat(forwarded(middleware, "10.0.0.5").remoteAddress()).isEqualTo("10.0.0.5");
    }

    @Test
    void forwardedProtoAndHostAreValidated() throws Exception {
        TrustedProxyMiddleware middleware = TrustedProxyMiddleware.of("10.0.0.0/8").withForwardedHeader();

        Context ctx = forwarded(middleware, "10.0.0.5", "for=203.0.113.7;proto=javascript;host=\"evil.example/x\"");
        assertThat(ctx.remoteAddress()).isEqualTo("203.0.113.7");
        assertThat((Object) ctx.attribute(TrustedProxyMiddleware.PROTO_ATTRIBUTE)).isNull();
        assertThat((Object) ctx.attribute(TrustedProxyMiddleware.HOST_ATTRIBUTE)).isNull();

        Context v6 = forwarded(middleware, "10.0.0.5", "for=203.0.113.7;host=\"[2001:db8::1]:8443\";proto=HTTPS");
        assertThat((String) v6.attribute(TrustedProxyMiddleware.HOST_ATTRIBUTE)).isEqualTo("[2001:db8::1]:8443");
        assertThat((String) v6.attribute(TrustedProxyMiddleware.PROTO_ATTRIBUTE)).isEqualTo("https");
    }

    @Test
    void theXForwardedForModeIgnoresTheForwardedHeader() throws Exception {
        TrustedProxyMiddleware middleware = TrustedProxyMiddleware.of("10.0.0.0/8");
        assertThat(forwarded(middleware, "10.0.0.5", "for=203.0.113.7").remoteAddress()).isEqualTo("10.0.0.5");
    }
}
