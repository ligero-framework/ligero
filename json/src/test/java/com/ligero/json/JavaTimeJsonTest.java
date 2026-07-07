package com.ligero.json;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * java.time types must round-trip as ISO-8601 strings (not fail, not numbers),
 * and unknown JSON fields must be ignored rather than rejected.
 */
class JavaTimeJsonTest {

    record Event(String name, Instant at, LocalDate day) {
    }

    @Test
    void serializesJavaTimeAsIsoStrings() {
        String json = Json.stringify(new Event("launch",
            Instant.parse("2026-07-07T10:15:30Z"), LocalDate.of(2026, 7, 7)));
        assertThat(json)
            .contains("\"at\":\"2026-07-07T10:15:30Z\"")
            .contains("\"day\":\"2026-07-07\"")
            .doesNotContain("1.7"); // not an epoch number
    }

    @Test
    void deserializesJavaTimeFromIsoStrings() {
        Event event = Json.parse(
            "{\"name\":\"launch\",\"at\":\"2026-07-07T10:15:30Z\",\"day\":\"2026-07-07\"}",
            Event.class);
        assertThat(event.at()).isEqualTo(Instant.parse("2026-07-07T10:15:30Z"));
        assertThat(event.day()).isEqualTo(LocalDate.of(2026, 7, 7));
    }

    @Test
    void ignoresUnknownFields() {
        Event event = Json.parse(
            "{\"name\":\"launch\",\"extra\":42,\"at\":\"2026-07-07T10:15:30Z\",\"day\":\"2026-07-07\"}",
            Event.class);
        assertThat(event.name()).isEqualTo("launch");
    }
}
