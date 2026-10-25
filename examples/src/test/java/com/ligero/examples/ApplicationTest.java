package com.ligero.examples;

import com.ligero.test.LigeroTest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationTest {

    @Test
    void usersCrudFlowsThroughAllLayers() {
        try (LigeroTest test = LigeroTest.start(Application.create(0))) {
            assertTrue(test.get("/api/v1/users").execute().body().contains("Ada Lovelace"));

            var created = test.post("/api/v1/users").json("{\"name\":\"Grace\"}").execute();
            assertEquals(201, created.status());

            assertEquals(400, test.post("/api/v1/users").json("{\"name\":\" \"}").execute().status());
            assertEquals(404, test.get("/api/v1/users/999").execute().status());
        }
    }
}
