package com.ligero.websocket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WsHandlerOriginTest {

    private final WsHandler handler = new WsHandler() { };

    @Test
    void sameOriginAndNonBrowserClientsAreAccepted() {
        assertThat(handler.acceptOrigin(null, "app.example.com")).isTrue();
        assertThat(handler.acceptOrigin("https://app.example.com", "app.example.com")).isTrue();
        assertThat(handler.acceptOrigin("https://app.example.com:443", "app.example.com")).isTrue();
        assertThat(handler.acceptOrigin("http://localhost:8080", "localhost:8080")).isTrue();
        assertThat(handler.acceptOrigin("http://APP.example.com", "app.example.com:80")).isTrue();
    }

    @Test
    void crossSiteHandshakesAreRejected() {
        assertThat(handler.acceptOrigin("https://evil.example", "app.example.com")).isFalse();
        assertThat(handler.acceptOrigin("https://app.example.com.evil.example", "app.example.com")).isFalse();
        assertThat(handler.acceptOrigin("http://localhost:3000", "localhost:8080")).isFalse();
        assertThat(handler.acceptOrigin("null", "app.example.com")).isFalse();
        assertThat(handler.acceptOrigin("https://app.example.com", null)).isFalse();
    }

    @Test
    void handlersCanAllowExtraOrigins() {
        WsHandler permissive = new WsHandler() {
            @Override
            public boolean acceptOrigin(String origin, String host) {
                return "https://partner.example".equals(origin) || WsHandler.super.acceptOrigin(origin, host);
            }
        };
        assertThat(permissive.acceptOrigin("https://partner.example", "app.example.com")).isTrue();
        assertThat(permissive.acceptOrigin("https://evil.example", "app.example.com")).isFalse();
    }
}
