package com.ligero.server;

import com.ligero.Ligero;
import com.ligero.config.LigeroConfig;
import com.ligero.websocket.WsHandler;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdkEngineWebSocketRejectionTest {

    @Test
    void jdkEngineFailsFastWhenWebSocketRoutesExist() {
        Ligero app = Ligero.create(LigeroConfig.builder()
            .environment(Map.of()).host("127.0.0.1").port(0).build());
        app.engine(new JdkServerEngine());
        app.websocket("/chat", new WsHandler() { });

        assertThatThrownBy(app::start)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ligero-server-jetty");
    }
}
