package com.ligero.beans;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the constructor the optional {@code ligero-processor} should use when
 * a class has more than one. With a single constructor it is unnecessary —
 * the processor picks that one.
 *
 * <p>Compile-time only, hence {@code SOURCE} retention: the processor emits a
 * plain {@code new Impl(b.get(...), ...)} call, so nothing about injection
 * exists at runtime.</p>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.CONSTRUCTOR)
public @interface Inject {
}
