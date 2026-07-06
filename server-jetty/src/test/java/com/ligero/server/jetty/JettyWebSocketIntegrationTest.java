package com.ligero.server.jetty;

import com.ligero.Ligero;
import com.ligero.config.LigeroConfig;
import com.ligero.websocket.WsHandler;
import com.ligero.websocket.WsSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end WebSocket tests over the Jetty engine using the JDK WS client. */
class JettyWebSocketIntegrationTest {

    private Ligero app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    private static Ligero newApp() {
        Ligero app = Ligero.create(LigeroConfig.builder()
            .environment(Map.of()).host("127.0.0.1").port(0).build());
        app.engine(new JettyServerEngine());
        return app;
    }

    @Test
    void echoConversationWorks() throws Exception {
        List<String> serverEvents = new CopyOnWriteArrayList<>();
        app = newApp();
        app.websocket("/echo", new WsHandler() {
            @Override
            public void onConnect(WsSession session) {
                serverEvents.add("connect " + session.path());
            }

            @Override
            public void onMessage(WsSession session, String message) {
                serverEvents.add("message " + message);
                session.send("echo: " + message);
            }

            @Override
            public void onClose(WsSession session, int statusCode, String reason) {
                serverEvents.add("close " + statusCode);
            }
        });
        app.start();

        List<String> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> twoMessages = new CompletableFuture<>();
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                received.add(data.toString());
                if (received.size() == 2) {
                    twoMessages.complete(null);
                }
                webSocket.request(1);
                return null;
            }
        };

        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:" + app.port() + "/echo"), listener)
            .get(5, TimeUnit.SECONDS);
        socket.sendText("hola", true).get(5, TimeUnit.SECONDS);
        socket.sendText("mundo", true).get(5, TimeUnit.SECONDS);
        twoMessages.get(5, TimeUnit.SECONDS);
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);

        assertThat(received).containsExactly("echo: hola", "echo: mundo");
        for (int i = 0; i < 50 && serverEvents.stream().noneMatch(e -> e.startsWith("close")); i++) {
            Thread.sleep(100);
        }
        assertThat(serverEvents).contains("connect /echo", "message hola", "message mundo");
        assertThat(serverEvents).anyMatch(e -> e.startsWith("close"));
    }

    @Test
    void httpRoutesStillWorkAlongsideWebSockets() throws Exception {
        app = newApp();
        app.get("/plain", ctx -> ctx.text("http ok"));
        app.websocket("/ws", new WsHandler() { });
        app.start();

        var response = HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + app.port() + "/plain")).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).isEqualTo("http ok");
    }
}
