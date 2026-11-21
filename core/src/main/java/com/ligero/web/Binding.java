package com.ligero.web;

import com.ligero.http.BadRequestException;
import com.ligero.spi.RequestValidator;

import java.math.BigDecimal;
import java.util.ServiceLoader;
import java.util.UUID;

/**
 * Runtime support for the code {@code ligero-processor} generates from typed
 * controllers: converts raw request strings to parameter types and turns
 * anything that does not convert into a {@code 400} naming the offending
 * parameter. Application code rarely needs to call it directly.
 *
 * <p>Every converter accepts {@code null} (parameter absent) and returns
 * {@code null}; generated code wraps required parameters in
 * {@link #required}.</p>
 */
public final class Binding {

    private Binding() {
    }

    /** Fails with 400 when a required parameter is absent. */
    public static String required(String raw, String kind, String name) {
        if (raw == null) {
            throw new BadRequestException("Missing required " + kind + " '" + name + "'");
        }
        return raw;
    }

    /** The value, or {@code fallback} when the parameter is absent. */
    public static String orDefault(String raw, String fallback) {
        return raw == null ? fallback : raw;
    }

    public static Integer toInt(String raw, String kind, String name) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw invalid(kind, name, "an integer");
        }
    }

    public static Long toLong(String raw, String kind, String name) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw invalid(kind, name, "an integer");
        }
    }

    public static Double toDouble(String raw, String kind, String name) {
        if (raw == null) {
            return null;
        }
        Double value;
        try {
            value = Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw invalid(kind, name, "a number");
        }
        if (value.isNaN() || value.isInfinite()) {
            throw invalid(kind, name, "a number");
        }
        return value;
    }

    /** Accepts exactly {@code true} or {@code false} (any case). */
    public static Boolean toBoolean(String raw, String kind, String name) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        throw invalid(kind, name, "true or false");
    }

    public static UUID toUuid(String raw, String kind, String name) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw invalid(kind, name, "a UUID");
        }
    }

    public static BigDecimal toBigDecimal(String raw, String kind, String name) {
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw invalid(kind, name, "a decimal number");
        }
    }

    /**
     * Matches the constant name, ignoring case. The constants are passed in
     * ({@code MyEnum.values()}) so no reflection is needed to list them.
     */
    public static <E extends Enum<E>> E toEnum(E[] constants, String raw, String kind, String name) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        StringBuilder allowed = new StringBuilder();
        for (E constant : constants) {
            if (constant.name().equalsIgnoreCase(value)) {
                return constant;
            }
            if (allowed.length() > 0) {
                allowed.append(", ");
            }
            allowed.append(constant.name());
        }
        throw invalid(kind, name, "one of: " + allowed);
    }

    /**
     * Validates a request body through the {@link RequestValidator} SPI.
     *
     * @throws IllegalStateException when no validator is on the classpath
     */
    public static <T> T valid(T body) {
        return requireValidator().validate(body);
    }

    /**
     * Fails fast — at startup, when routes are mounted — if a controller uses
     * {@code @Valid} but no validator module is present.
     */
    public static RequestValidator requireValidator() {
        RequestValidator validator = ValidatorHolder.INSTANCE;
        if (validator == null) {
            throw new IllegalStateException(
                "@Valid needs a RequestValidator. Add ligero-validation (or another implementation) to the classpath.");
        }
        return validator;
    }

    private static BadRequestException invalid(String kind, String name, String expected) {
        // the raw value is deliberately not echoed back
        String label = Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
        return new BadRequestException(label + " '" + name + "' must be " + expected);
    }

    /** Resolved once, on first use. */
    private static final class ValidatorHolder {
        static final RequestValidator INSTANCE =
            ServiceLoader.load(RequestValidator.class).findFirst().orElse(null);
    }
}
