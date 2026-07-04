package com.ligero.http;

/** 403 Forbidden. */
public class ForbiddenException extends HttpException {

    public ForbiddenException(String message) {
        super(403, message);
    }
}
