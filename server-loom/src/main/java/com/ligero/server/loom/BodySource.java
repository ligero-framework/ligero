package com.ligero.server.loom;

import java.io.InputStream;

/** Where a request's body comes from: an HTTP/1.1 connection or an HTTP/2 stream. */
interface BodySource {

    /** The whole body, at most {@code maxBytes} long. */
    byte[] readBody(long maxBytes);

    /** The body as a one-shot stream that is not buffered in memory. */
    InputStream openBodyStream(long maxBytes);

    /** Address of the client. */
    String remoteAddress();
}
