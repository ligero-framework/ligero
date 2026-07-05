package com.ligero.http;

import com.ligero.testutil.FakeRequest;
import com.ligero.testutil.FakeResponse;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceRegistryTest {

    interface Greeter {
        String greet();
    }

    @Test
    void resolvesRegisteredService() {
        Greeter greeter = () -> "hola";
        Context ctx = new Context(FakeRequest.of("GET", "/"), new FakeResponse(), "/",
            null, null, Map.of(Greeter.class, greeter));
        assertThat(ctx.get(Greeter.class).greet()).isEqualTo("hola");
    }

    @Test
    void missingServiceFailsWithGuidance() {
        Context ctx = new Context(FakeRequest.of("GET", "/"), new FakeResponse(), "/", null, null);
        assertThatThrownBy(() -> ctx.get(Greeter.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.register");
    }
}
