package com.ligero.http;

/** 401 Unauthorized. */
public class UnauthorizedException extends HttpException {

    public UnauthorizedException(String message) {
        super(401, message);
    }
}
