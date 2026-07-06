package com.ligero.beans.stereotype;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stereotype marker for the dependency dashboard and diagnostics. Pure
 * metadata: wiring stays in explicit {@code bind(...)} lambdas — this
 * annotation never triggers scanning or reflection-based injection.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Service {
}
