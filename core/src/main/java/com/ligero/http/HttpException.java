package com.ligero.http;

/**
 * Exception carrying an HTTP status. Thrown from handlers or middleware, it
 * is mapped by the framework to a response with that status and a JSON body,
 * without leaking stack traces to the client.
 */
public class HttpException extends RuntimeException {

    private final int status;

    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public HttpException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
