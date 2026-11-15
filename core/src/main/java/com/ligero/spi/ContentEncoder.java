package com.ligero.spi;

import java.io.OutputStream;

/**
 * SPI for response compression codecs beyond gzip, discovered via
 * {@link java.util.ServiceLoader} — for example {@code ligero-compress-brotli}.
 * An engine that supports it picks, among the encodings the client accepts,
 * the available encoder with the highest {@link #priority()}.
 */
public interface ContentEncoder {

    /** The {@code Content-Encoding} token, e.g. {@code "br"}. */
    String encoding();

    /** Preference among encodings a client accepts; gzip is 0. */
    default int priority() {
        return 0;
    }

    /** False when the codec cannot run here (e.g. its native library is missing); it is then skipped. */
    default boolean available() {
        return true;
    }

    /** Compresses a complete body. */
    byte[] encode(byte[] content);

    /**
     * Wraps a stream for incremental compression. {@code flush()} on the
     * returned stream must push everything written so far to {@code out}, so
     * streamed responses (Server-Sent Events) are not held back.
     */
    OutputStream wrap(OutputStream out);
}
