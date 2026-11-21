package com.ligero.client;

import com.ligero.http.ProblemDetail;

import java.util.Optional;

/**
 * The server answered with a status other than {@code 2xx} — an error, or a
 * redirect that was not followed (too many hops). The response
 * is available, and so is its RFC 9457 problem when the server sent one.
 *
 * <pre>{@code
 * try {
 *     orders.place(order);
 * } catch (HttpStatusException e) {
 *     if (e.status() == 409) {
 *         e.problem().ifPresent(problem -> log.warn("Rejected: {}", problem.detail()));
 *     }
 * }
 * }</pre>
 */
public class HttpStatusException extends RestClientException {

    private final transient ClientResponse response;

    public HttpStatusException(ClientResponse response) {
        super(response.request() + " answered " + response.status());
        this.response = response;
    }

    public int status() {
        return response.status();
    }

    public ClientResponse response() {
        return response;
    }

    /** The problem details in the response body, if it was {@code application/problem+json}. */
    public Optional<ProblemDetail> problem() {
        return response.problem();
    }
}
