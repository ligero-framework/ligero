package com.ligero.json;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Error paths of the {@link Json} facade: both {@code stringify} and
 * {@code parse} wrap Jackson failures in a {@link Json.JsonException}.
 */
class JsonErrorTest {

    @Test
    void stringifyWrapsSerializationErrors() {
        // An empty POJO can't be serialized (FAIL_ON_EMPTY_BEANS) -> wrapped.
        assertThatThrownBy(() -> Json.stringify(new Object()))
            .isInstanceOf(Json.JsonException.class)
            .hasMessageContaining("convertir");
    }

    @Test
    void parseWrapsMalformedJson() {
        assertThatThrownBy(() -> Json.parse("{not valid", Map.class))
            .isInstanceOf(Json.JsonException.class)
            .hasMessageContaining("analizar");
    }

    @Test
    void jsonExceptionKeepsMessageAndCause() {
        Json.JsonException withMessage = new Json.JsonException("boom");
        assertThat(withMessage).hasMessage("boom").hasNoCause();

        Throwable cause = new IllegalStateException("root");
        Json.JsonException withCause = new Json.JsonException("wrap", cause);
        assertThat(withCause).hasMessage("wrap").hasCause(cause);
    }
}
