package com.ligero.openapi;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns Java records into OpenAPI 3 schema objects. Reflection is used once, at
 * document-build time (never on the request path), so this stays compatible
 * with the framework's reflection-free runtime.
 *
 * <p>Nested records become {@code $ref}s and are collected so the caller can add
 * them to {@code components/schemas}. Collections map to arrays; the usual
 * scalars map to their JSON types.</p>
 */
final class Schemas {

    private Schemas() {
    }

    static String name(Class<?> type) {
        return type.getSimpleName();
    }

    /**
     * The schema for {@code type}. Any nested record types encountered are added
     * to {@code collected} (name -> schema) so they can be emitted as components.
     */
    static Map<String, Object> of(Class<?> type, Map<String, Object> collected) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            properties.put(component.getName(),
                schemaFor(component.getType(), component.getGenericType(), collected));
            if (component.getType().isPrimitive()) {
                required.add(component.getName());
            }
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> schemaFor(Class<?> type, Type generic, Map<String, Object> collected) {
        if (type == String.class || type == char.class || type == Character.class) {
            return Map.of("type", "string");
        }
        if (type == boolean.class || type == Boolean.class) {
            return Map.of("type", "boolean");
        }
        if (isInteger(type)) {
            return Map.of("type", "integer");
        }
        if (isNumber(type)) {
            return Map.of("type", "number");
        }
        if (type.isEnum()) {
            List<String> values = new ArrayList<>();
            for (Object constant : type.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            return Map.of("type", "string", "enum", values);
        }
        if (type == LocalDate.class) {
            return Map.of("type", "string", "format", "date");
        }
        if (Temporal.class.isAssignableFrom(type)) {
            return Map.of("type", "string", "format", "date-time");
        }
        if (type.isArray()) {
            return Map.of("type", "array", "items",
                schemaFor(type.getComponentType(), type.getComponentType(), collected));
        }
        if (Collection.class.isAssignableFrom(type)) {
            Class<?> element = elementType(generic);
            Map<String, Object> items = element == null
                ? Map.of("type", "object")
                : schemaFor(element, element, collected);
            return Map.of("type", "array", "items", items);
        }
        if (type.isRecord()) {
            String name = name(type);
            if (!collected.containsKey(name)) {
                collected.put(name, new LinkedHashMap<>());      // reserve (guards cycles)
                collected.put(name, of(type, collected));
            }
            return Map.of("$ref", "#/components/schemas/" + name);
        }
        return Map.of("type", "object");
    }

    private static Class<?> elementType(Type generic) {
        if (generic instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> element) {
                return element;
            }
        }
        return null;
    }

    private static boolean isInteger(Class<?> type) {
        return type == int.class || type == Integer.class
            || type == long.class || type == Long.class
            || type == short.class || type == Short.class
            || type == byte.class || type == Byte.class
            || type == BigInteger.class;
    }

    private static boolean isNumber(Class<?> type) {
        return type == double.class || type == Double.class
            || type == float.class || type == Float.class
            || type == BigDecimal.class;
    }
}
