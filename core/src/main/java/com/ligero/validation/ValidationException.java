package com.ligero.validation;

import com.ligero.http.BadRequestException;
import com.ligero.http.ProblemDetail;

import java.util.List;

/** 400 Bad Request carrying the list of failed validation checks. */
public class ValidationException extends BadRequestException {

    private final List<String> errors;

    public ValidationException(List<String> errors) {
        super("Validation failed: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }

    /** As a problem: {@code "detail":"Validation failed"} plus an {@code "errors"} array. */
    @Override
    public ProblemDetail toProblem() {
        return ProblemDetail.of(400, "Validation failed").with("errors", errors);
    }
}
