package com.ligero.client;

/**
 * A request made with a {@link RestClient} failed: the connection could not
 * be made or was cut, it timed out, the response was too large, or the
 * server answered with an error status ({@link HttpStatusException}).
 */
public class RestClientException extends RuntimeException {

    public RestClientException(String message) {
        super(message);
    }

    public RestClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
