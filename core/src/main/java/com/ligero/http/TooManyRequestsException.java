package com.ligero.http;

/** 429 Too Many Requests. */
public class TooManyRequestsException extends HttpException {

    public TooManyRequestsException(String message) {
        super(429, message);
    }
}
