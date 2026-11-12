package com.ligero.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Status code of a successful response from a controller method, when it is
 * not the default ({@code 200} with a body, {@code 204} without). Return a
 * {@link com.ligero.http.Response} instead when the status is decided at
 * runtime.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Status {

    int value();
}
