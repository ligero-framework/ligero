package com.ligero.json;

import com.ligero.http.BadRequestException;
import com.ligero.spi.BodyMapper;

/**
 * Jackson-backed {@link BodyMapper}, registered via
 * {@code META-INF/services} so the core discovers it automatically when
 * {@code ligero-json} is on the classpath.
 */
public final class JacksonBodyMapper implements BodyMapper {

    @Override
    public String writeJson(Object value) {
        return Json.stringify(value);
    }

    @Override
    public <T> T readJson(String json, Class<T> type) {
        try {
            return Json.parse(json, type);
        } catch (Json.JsonException e) {
            // a client sending broken JSON is a 400, not a server error
            throw new BadRequestException("Malformed JSON body");
        }
    }
}
