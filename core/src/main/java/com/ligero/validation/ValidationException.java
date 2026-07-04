package com.ligero.validation;

import com.ligero.http.BadRequestException;

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
}
