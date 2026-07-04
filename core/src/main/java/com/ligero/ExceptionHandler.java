package com.ligero;

import com.ligero.http.Context;

/**
 * Handles exceptions of type {@code T} thrown by handlers or middleware.
 * Registered with {@link Ligero#exception(Class, ExceptionHandler)}.
 */
@FunctionalInterface
public interface ExceptionHandler<T extends Throwable> {

    void handle(T exception, Context ctx) throws Exception;
}
