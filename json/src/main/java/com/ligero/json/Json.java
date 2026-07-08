package com.ligero.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Utilidades para trabajar con JSON.
 */
public class Json {
    private static final ObjectMapper mapper = new ObjectMapper()
        // java.time support: serialize Instant/LocalDate/... as ISO-8601 strings
        // (e.g. "2026-07-07T10:15:30Z") rather than failing or emitting numbers.
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        // DX default: ignore unknown JSON fields instead of 400-ing on them.
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Json() {
        // Clase de utilidad, no se debe instanciar
    }
    
    /**
     * Convierte un objeto a una cadena JSON.
     * @param obj El objeto a convertir
     * @return Una representación JSON del objeto
     */
    public static String stringify(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new JsonException("Error al convertir objeto a JSON", e);
        }
    }
    
    /**
     * Convierte una cadena JSON a un objeto del tipo especificado.
     * @param json La cadena JSON
     * @param type La clase del objeto resultante
     * @return Un objeto del tipo especificado
     */
    public static <T> T parse(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new JsonException("Error al analizar JSON", e);
        }
    }
    
    /**
     * Excepción para errores de procesamiento JSON.
     */
    public static class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
        
        public JsonException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
