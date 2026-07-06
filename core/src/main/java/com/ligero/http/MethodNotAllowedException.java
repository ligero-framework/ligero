package com.ligero.http;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** 405 Method Not Allowed. Carries the set of allowed methods for the path. */
public class MethodNotAllowedException extends HttpException {

    private final Set<String> allowedMethods;

    public MethodNotAllowedException(String method, Set<String> allowedMethods) {
        super(405, "Method " + method + " not allowed");
        this.allowedMethods = Collections.unmodifiableSet(new TreeSet<>(allowedMethods));
    }

    public Set<String> getAllowedMethods() {
        return allowedMethods;
    }
}
