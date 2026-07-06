package com.ligero.http;

/** 404 Not Found. */
public class NotFoundException extends HttpException {

    public NotFoundException(String message) {
        super(404, message);
    }
}
