package com.ligero.web;

import com.ligero.http.BadRequestException;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BindingTest {

    enum Color { RED, GREEN }

    @Test
    void convertsWellFormedValues() {
        assertThat(Binding.toInt(" 42 ", "query parameter", "page")).isEqualTo(42);
        assertThat(Binding.toLong("9000000000", "path parameter", "id")).isEqualTo(9_000_000_000L);
        assertThat(Binding.toDouble("1.5", "query parameter", "ratio")).isEqualTo(1.5);
        assertThat(Binding.toBoolean("TRUE", "query parameter", "active")).isTrue();
        assertThat(Binding.toBigDecimal("19.99", "query parameter", "price")).isEqualTo(new BigDecimal("19.99"));
        UUID id = UUID.randomUUID();
        assertThat(Binding.toUuid(id.toString(), "path parameter", "id")).isEqualTo(id);
        assertThat(Binding.toEnum(Color.values(), "green", "query parameter", "color")).isEqualTo(Color.GREEN);
    }

    @Test
    void absentValuesStayNull() {
        assertThat(Binding.toInt(null, "query parameter", "page")).isNull();
        assertThat(Binding.toEnum(Color.values(), null, "query parameter", "color")).isNull();
        assertThat(Binding.orDefault(null, "1")).isEqualTo("1");
        assertThat(Binding.orDefault("7", "1")).isEqualTo("7");
    }

    @Test
    void badValuesAre400sThatNameTheParameterButNotTheValue() {
        assertThatThrownBy(() -> Binding.toInt("<script>", "query parameter", "page"))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("Query parameter 'page' must be an integer");
        assertThatThrownBy(() -> Binding.toBoolean("yes", "query parameter", "active"))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("true or false");
        assertThatThrownBy(() -> Binding.toDouble("NaN", "query parameter", "ratio"))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Binding.toUuid("nope", "path parameter", "id"))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Binding.toEnum(Color.values(), "blue", "query parameter", "color"))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("RED, GREEN");
        assertThatThrownBy(() -> Binding.required(null, "header", "X-Tenant"))
            .isInstanceOf(BadRequestException.class).hasMessage("Missing required header 'X-Tenant'");
    }
}
