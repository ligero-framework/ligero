package com.ligero.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Fluent validator for deserialized request bodies. Checks are accumulated;
 * {@link #get()} either returns the value or throws a
 * {@link ValidationException} (mapped to 400 with the collected messages).
 *
 * <pre>{@code
 * User user = ctx.bodyValidator(User.class)
 *     .check(u -> u.name() != null && !u.name().isBlank(), "name is required")
 *     .check(u -> u.age() >= 0, "age must be positive")
 *     .get();
 * }</pre>
 */
public final class BodyValidator<T> {

    private final T value;
    private final List<String> errors = new ArrayList<>();

    public BodyValidator(T value) {
        this.value = value;
    }

    public BodyValidator<T> check(Predicate<T> predicate, String message) {
        boolean ok;
        try {
            ok = predicate.test(value);
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) {
            errors.add(message);
        }
        return this;
    }

    public T get() {
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return value;
    }
}
