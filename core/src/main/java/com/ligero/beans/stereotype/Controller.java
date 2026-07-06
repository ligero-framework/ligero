package com.ligero.beans.stereotype;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stereotype marker for the dependency dashboard and diagnostics. Pure
 * metadata at runtime: wiring stays in explicit {@code bind(...)} lambdas —
 * this annotation never triggers scanning or reflection-based injection.
 *
 * <p>The optional {@code ligero-processor} reads it at <em>compile time</em>
 * to generate those same explicit bindings (still no runtime reflection);
 * {@link #as()} picks the binding key when several interfaces are implemented.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Controller {

    /** Binding key for the optional processor; defaults to the single implemented interface (or the class itself). */
    Class<?> as() default Void.class;
}
