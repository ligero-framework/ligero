/** Ligero JSON: Jackson-based {@code BodyMapper} implementation. */
module com.ligero.json {
    requires com.ligero.core;
    requires com.fasterxml.jackson.databind;

    exports com.ligero.json;

    provides com.ligero.spi.BodyMapper with com.ligero.json.JacksonBodyMapper;
}
