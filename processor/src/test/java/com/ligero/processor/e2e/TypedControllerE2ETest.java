package com.ligero.processor.e2e;

import com.ligero.Modules;
import com.ligero.generated.GeneratedModules;
import com.ligero.test.LigeroTest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BookController} is compiled with the processor as part of this
 * module's test sources; here the generated wiring is installed on a real
 * server and exercised over HTTP — binding, conversion errors, validation
 * and every kind of return value.
 */
class TypedControllerE2ETest {

    private static LigeroTest server;

    @BeforeAll
    static void start() {
        server = LigeroTest.create(app -> Modules.install(app, GeneratedModules.all()));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void createReturnsATypedResponseWithStatusAndLocation() {
        LigeroTest.TestResponse created = server.post("/api/books")
            .json("{\"title\":\"Dune\",\"genre\":\"FICTION\"}").execute();

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.header("Location")).matches("/api/books/\\d+");
        assertThat(created.body()).contains("\"title\":\"Dune\"").contains("\"genre\":\"FICTION\"");

        LigeroTest.TestResponse fetched = server.get(created.header("Location")).execute();
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.body()).contains("\"title\":\"Dune\"");
    }

    @Test
    void emptyOptionalIs404() {
        assertThat(server.get("/api/books/999999").execute().status()).isEqualTo(404);
    }

    @Test
    void badPathParameterIs400NamingTheParameter() {
        LigeroTest.TestResponse response = server.get("/api/books/abc").execute();
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("Path parameter 'id' must be an integer").doesNotContain("abc");
    }

    @Test
    void invalidBodyIs400ListingViolations() {
        LigeroTest.TestResponse response = server.post("/api/books").json("{\"title\":\"  \"}").execute();
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("title").contains("genre");
    }

    @Test
    void missingBodyIs400() {
        assertThat(server.post("/api/books").execute().status()).isEqualTo(400);
    }

    @Test
    void statusAnnotationAndQueryBinding() {
        LigeroTest.TestResponse response = server.post("/api/books/quick?title=Solaris").execute();
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body()).contains("Solaris");
    }

    @Test
    void enumAndDefaultedQueryParameters() {
        server.post("/api/books").json("{\"title\":\"Cosmos\",\"genre\":\"SCIENCE\"}").execute();

        LigeroTest.TestResponse science = server.get("/api/books?genre=science").execute();
        assertThat(science.status()).isEqualTo(200);
        assertThat(science.body()).contains("Cosmos").doesNotContain("FICTION");

        assertThat(server.get("/api/books?limit=0").execute().body()).isEqualTo("[]");

        LigeroTest.TestResponse unknown = server.get("/api/books?genre=poetry").execute();
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.body()).contains("FICTION, SCIENCE");

        assertThat(server.get("/api/books?limit=many").execute().status()).isEqualTo(400);
    }

    @Test
    void voidMethodAnswers204AndErrorsStillMap() {
        String location = server.post("/api/books")
            .json("{\"title\":\"Temp\",\"genre\":\"FICTION\"}").execute().header("Location");

        assertThat(server.delete(location).execute().status()).isEqualTo(204);
        assertThat(server.delete(location).execute().status()).isEqualTo(404);
    }

    @Test
    void stringReturnIsPlainText() {
        String location = server.post("/api/books")
            .json("{\"title\":\"Neuromancer\",\"genre\":\"FICTION\"}").execute().header("Location");

        LigeroTest.TestResponse response = server.get(location + "/title").execute();
        assertThat(response.header("Content-Type")).startsWith("text/plain");
        assertThat(response.body()).isEqualTo("Neuromancer");
    }

    @Test
    void headerParametersWithDefaultsAndOptionality() {
        assertThat(server.get("/api/books/whoami").header("X-Tenant", "acme").header("X-Page", "3")
            .execute().body()).isEqualTo("acme:3");
        assertThat(server.get("/api/books/whoami").execute().body()).isEqualTo("null:1");
        assertThat(server.get("/api/books/whoami").header("X-Page", "x").execute().status()).isEqualTo(400);
    }

    @Test
    void repeatedQueryParametersBindToAList() {
        assertThat(server.get("/api/books/tags?tag=a&tag=b").execute().body()).isEqualTo("[\"a\",\"b\"]");
    }

    @Test
    void routeAnnotationCoversOtherHttpMethods() {
        server.post("/api/books").json("{\"title\":\"Foundation\",\"genre\":\"FICTION\"}").execute();
        LigeroTest.TestResponse response = server.request("QUERY", "/api/books/search").body("Found").execute();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("Foundation");
    }

    @Test
    void methodsMayStillWriteThroughTheContext() {
        LigeroTest.TestResponse response = server.get("/api/books/manual").execute();
        assertThat(response.status()).isEqualTo(418);
        assertThat(response.body()).isEqualTo("handled by hand");
    }
}
