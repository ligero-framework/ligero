package com.ligero.processor.e2e.versioned;

import com.ligero.Modules;
import com.ligero.generated.GeneratedModules;
import com.ligero.test.LigeroTest;
import com.ligero.versioning.ApiVersioning;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionedControllerE2ETest {

    private static LigeroTest server;

    @BeforeAll
    static void start() {
        server = LigeroTest.create(app -> {
            app.use(ApiVersioning.builder().header("API-Version").supported("1", "2", "3").build());
            Modules.install(app, GeneratedModules.all());
        });
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void eachVersionReachesItsMethod() {
        assertThat(server.get("/api/catalog/7").header("API-Version", "1").execute().body())
            .contains("\"label\":\"item 7\"");
        assertThat(server.get("/api/catalog/7").header("API-Version", "2").execute().body())
            .contains("\"name\":\"item 7\"");
        assertThat(server.get("/api/catalog/7").header("API-Version", "3").execute().body())
            .contains("\"version\":\"2+\"");
    }

    @Test
    void theUnversionedMethodServesRequestsWithoutAVersion() {
        assertThat(server.get("/api/catalog/7").execute().body()).isEqualTo("unversioned 7");
    }

    @Test
    void aVersionTheRouteDoesNotHaveIs404AndAnUnsupportedOneIs400() {
        LigeroTest.TestResponse missing = server.get("/api/catalog/legacy").header("API-Version", "2").execute();
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.header("Content-Type")).startsWith("application/problem+json");
        assertThat(server.get("/api/catalog/legacy").header("API-Version", "1").execute().body()).isEqualTo("legacy");
        assertThat(server.get("/api/catalog/7").header("API-Version", "9").execute().status()).isEqualTo(400);
    }
}
