package com.ligero.http;

/** 400 Bad Request. */
public class BadRequestException extends HttpException {

    public BadRequestException(String message) {
        super(400, message);
    }
}
