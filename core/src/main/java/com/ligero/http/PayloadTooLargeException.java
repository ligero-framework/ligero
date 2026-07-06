package com.ligero.http;

/** 413 Payload Too Large. Thrown when the request body exceeds the configured limit. */
public class PayloadTooLargeException extends HttpException {

    public PayloadTooLargeException(long limitBytes) {
        super(413, "Request body exceeds the configured limit of " + limitBytes + " bytes");
    }
}
