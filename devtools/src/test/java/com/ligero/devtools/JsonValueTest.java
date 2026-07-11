package com.ligero.devtools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JsonValueTest {

    record Point(int x, int y) {
    }

    static class Bean {
        public String getName() {
            return "ada";
        }

        public boolean isActive() {
            return true;
        }

        public String getBoom() {
            throw new IllegalStateException("nope");  // must be skipped, not fatal
        }
    }

    @Test
    void serializesScalarsAndNull() {
        assertThat(JsonValue.of(null)).isEqualTo("null");
        assertThat(JsonValue.of(true)).isEqualTo("true");
        assertThat(JsonValue.of(42)).isEqualTo("42");
        assertThat(JsonValue.of(3.5)).isEqualTo("3.5");
        assertThat(JsonValue.of("hi")).isEqualTo("\"hi\"");
        assertThat(JsonValue.of('a')).isEqualTo("\"a\"");
    }

    @Test
    void serializesNonFiniteNumbersAsStrings() {
        assertThat(JsonValue.of(Double.NaN)).isEqualTo("\"NaN\"");
        assertThat(JsonValue.of(Double.POSITIVE_INFINITY)).isEqualTo("\"Infinity\"");
    }

    @Test
    void serializesCollectionsMapsAndArrays() {
        assertThat(JsonValue.of(List.of(1, 2, 3))).isEqualTo("[1,2,3]");
        assertThat(JsonValue.of(new int[] {1, 2})).isEqualTo("[1,2]");
        assertThat(JsonValue.of(Map.of("a", 1))).isEqualTo("{\"a\":1}");
        assertThat(JsonValue.of(Optional.of("x"))).isEqualTo("\"x\"");
        assertThat(JsonValue.of(Optional.empty())).isEqualTo("null");
    }

    @Test
    void serializesRecordsByComponent() {
        assertThat(JsonValue.of(new Point(1, 2)))
            .isEqualTo("{\"x\":1,\"y\":2}");
    }

    @Test
    void serializesBeansByGetterAndSkipsThrowingAccessors() {
        String json = JsonValue.of(new Bean());
        assertThat(json).contains("\"name\":\"ada\"").contains("\"active\":true");
        assertThat(json).doesNotContain("boom");  // throwing getter skipped
    }

    @Test
    void wrapsMethodArgumentsAsAnArray() {
        assertThat(JsonValue.array(null)).isEqualTo("[]");
        assertThat(JsonValue.array(new Object[0])).isEqualTo("[]");
        assertThat(JsonValue.array(new Object[] {7, "a"})).isEqualTo("[7,\"a\"]");
    }

    @Test
    void guardsAgainstReferenceCycles() {
        Object[] self = new Object[1];
        self[0] = self;
        assertThat(JsonValue.of(self)).contains("recursive");  // does not overflow the stack
    }
}
