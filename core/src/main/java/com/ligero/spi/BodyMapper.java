package com.ligero.spi;

/**
 * SPI for JSON (de)serialization so the core has no dependency on any JSON
 * library. {@code ligero-json} provides the Jackson-based implementation and
 * registers it via {@link java.util.ServiceLoader}.
 */
public interface BodyMapper {

    String writeJson(Object value);

    <T> T readJson(String json, Class<T> type);
}
