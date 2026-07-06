package com.ligero.router;

import com.ligero.http.Handler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouterTest {

    private final Handler noop = ctx -> { };

    @Test
    void isolatesMethods() {
        Router router = new Router();
        router.add("GET", "/users", noop);

        assertThat(router.match("GET", "/users")).isNotNull();
        assertThat(router.match("POST", "/users")).isNull();
    }

    @Test
    void methodIsCaseInsensitive() {
        Router router = new Router();
        router.add("get", "/users", noop);
        assertThat(router.match("GET", "/users")).isNotNull();
    }

    @Test
    void normalizesRoutePathsOnRegistration() {
        Router router = new Router();
        router.add("GET", "users//all/", noop);
        assertThat(router.match("GET", "/users/all")).isNotNull();
    }

    @Test
    void reportsAllowedMethodsForPath() {
        Router router = new Router();
        router.add("GET", "/users/{id}", noop);
        router.add("DELETE", "/users/{id}", noop);

        assertThat(router.allowedMethods("/users/1")).containsExactly("DELETE", "GET");
        assertThat(router.allowedMethods("/none")).isEmpty();
    }

    @Test
    void exposesRegisteredRoutes() {
        Router router = new Router();
        router.add("GET", "/a", noop);
        router.add("POST", "/b", noop);

        assertThat(router.routes()).containsKeys("GET", "POST");
        assertThat(router.routes().get("GET")).containsExactly("/a");
    }
}
