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

    /**
     * This error as an RFC 9457 problem: the status, its reason phrase as
     * title and the message as detail. Subclasses add members (validation
     * errors, allowed methods, ...).
     */
    public ProblemDetail toProblem() {
        return ProblemDetail.of(status, getMessage());
    }
}
