package com.ligero.devtools;

import com.ligero.Ligero;
import com.ligero.beans.Beans;
import com.ligero.beans.stereotype.Repository;
import com.ligero.beans.stereotype.Service;
import com.ligero.test.LigeroTest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots a small layered app (route -> GreetService -> NameRepo, both bound
 * as interfaces) with devtools installed and checks the dashboard, the graph
 * API, the trace API and the live SSE stream over real HTTP.
 */
class DevtoolsE2ETest {

    interface NameRepo {
        String find(int id);
    }

    interface GreetService {
        String greet(int id);
    }

    @Repository
    static class FixedNameRepo implements NameRepo {
        @Override
        public String find(int id) {
            return "user-" + id;
        }
    }

    @Service
    static class DefaultGreetService implements GreetService {
        private final NameRepo repo;

        DefaultGreetService(NameRepo repo) {
            this.repo = repo;
        }

        @Override
        public String greet(int id) {
            return "hello " + repo.find(id);
        }
    }

    private static LigeroTest server;

    @BeforeAll
    static void start() {
        server = LigeroTest.create(app -> {
            Devtools devtools = Devtools.create();
            Beans beans = Beans.builder()
                .bind(NameRepo.class, b -> new FixedNameRepo())
                .bind(GreetService.class, b -> new DefaultGreetService(b.get(NameRepo.class)))
                .instrument(devtools.recorder())
                .start();
            app.beans(beans);
            devtools.install(app, beans);
            app.get("/greet/{id}", ctx ->
                ctx.text(ctx.get(GreetService.class).greet(ctx.pathParamAsInt("id"))));
            app.get("/api/greet/{id}", ctx ->
                ctx.json(java.util.Map.of("message", ctx.get(GreetService.class).greet(ctx.pathParamAsInt("id")))));
        });
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void servesTheDashboard() {
        LigeroTest.TestResponse response = server.get("/ligero/dev").execute();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).contains("text/html");
        assertThat(response.body()).contains("Ligero Devtools").contains("api/stream");
    }

    @Test
    void graphApiExposesNodesEdgesAndImplementationStereotypes() {
        LigeroTest.TestResponse response = server.get("/ligero/dev/api/graph").execute();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).contains("application/json");
        assertThat(response.body())
            .contains("\"type\":\"" + NameRepo.class.getName() + "\",\"stereotype\":\"repository\"")
            .contains("\"type\":\"" + GreetService.class.getName() + "\",\"stereotype\":\"service\"")
            .contains("\"from\":\"" + GreetService.class.getName() + "\",\"to\":\"" + NameRepo.class.getName() + "\"");
    }

    @Test
    void tracesARequestThroughServiceAndRepositoryLayers() {
        LigeroTest.TestResponse greeting = server.get("/greet/7").execute();
        assertThat(greeting.body()).isEqualTo("hello user-7");

        String traces = server.get("/ligero/dev/api/requests").execute().body();
        assertThat(traces)
            .contains("\"path\":\"/greet/7\"")
            .contains("\"status\":200")
            // service call at depth 0, repository call nested at depth 1
            .contains("\"depth\":0,\"bean\":\"DefaultGreetService\",\"declaredBy\":\"GreetService\","
                      + "\"stereotype\":\"service\",\"method\":\"greet\",\"args\":[7]")
            .contains("\"depth\":1,\"bean\":\"FixedNameRepo\",\"declaredBy\":\"NameRepo\","
                      + "\"stereotype\":\"repository\",\"method\":\"find\",\"args\":[7]")
            .contains("\"result\":\"hello user-7\"")
            .contains("\"result\":\"user-7\"");
    }

    @Test
    void routesApiListsRegisteredRoutes() {
        String routes = server.get("/ligero/dev/api/routes").execute().body();
        assertThat(routes)
            .contains("{\"method\":\"GET\",\"path\":\"/api/greet/{id}\"}")
            .contains("{\"method\":\"GET\",\"path\":\"/greet/{id}\"}");
    }

    @Test
    void capturesRouteRequestAndJsonResponseAndCorrelationId() {
        server.get("/api/greet/3").header("X-Ligero-Dev", "fired123").execute();

        String traces = server.get("/ligero/dev/api/requests").execute().body();
        assertThat(traces)
            .contains("\"id\":\"fired123\"")                              // correlation header -> trace id
            .contains("\"route\":\"/api/greet/{id}\"")                    // matched route pattern
            .contains("\"pathParams\":{\"id\":\"3\"}")                    // request inputs as JSON
            .contains("\"response\":{\"message\":\"hello user-3\"}");     // ctx.json body captured as JSON
    }

    @Test
    void devtoolsEndpointsAreNotTracedThemselves() {
        server.get("/ligero/dev/api/graph").execute();
        String traces = server.get("/ligero/dev/api/requests").execute().body();
        assertThat(traces).doesNotContain("\"path\":\"/ligero/dev");
    }

    @Test
    void streamPushesCompletedTracesLive() throws Exception {
        CompletableFuture<String> firstEvent = new CompletableFuture<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) URI
                    .create(server.baseUrl() + "/ligero/dev/api/stream").toURL().openConnection();
                connection.setReadTimeout(10_000);
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.startsWith("data: ") && line.contains("\"path\":\"/greet/9\"")) {
                            firstEvent.complete(line.substring("data: ".length()));
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                firstEvent.completeExceptionally(e);
            }
        });

        // The SSE handler subscribes right after committing headers; retry the
        // traced request until the event lands (bounded by the future timeout).
        String data = null;
        for (int attempt = 0; attempt < 25 && data == null; attempt++) {
            server.get("/greet/9").execute();
            try {
                data = firstEvent.get(400, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException retry) {
                // not yet — fire again
            }
        }
        assertThat(data)
            .isNotNull()
            .contains("\"path\":\"/greet/9\"")
            .contains("\"bean\":\"FixedNameRepo\"");
        reader.interrupt();
    }
}
