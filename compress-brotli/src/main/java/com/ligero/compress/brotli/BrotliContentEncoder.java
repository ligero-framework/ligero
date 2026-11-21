package com.ligero.compress.brotli;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream;
import com.aayushatharva.brotli4j.encoder.Encoder;
import com.ligero.spi.ContentEncoder;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/**
 * Brotli ({@code Content-Encoding: br}) for responses. Brotli typically
 * produces output 15-25 % smaller than gzip for text, JSON and JavaScript and
 * every current browser accepts it, so an engine that supports the
 * {@link ContentEncoder} SPI prefers it over gzip whenever the client does.
 *
 * <p>Registered through {@code ServiceLoader}: adding
 * {@code ligero-compress-brotli} to the classpath is all it takes (compression
 * itself is switched on with {@code gzip(true)} / {@code LIGERO_GZIP=true}).
 * The codec is native code; where its library is not available the encoder
 * reports itself unavailable and responses fall back to gzip.</p>
 *
 * <p>Quality 4 is used: at that level Brotli is about as fast as gzip's
 * default while still compressing better, which is the right trade-off for
 * content generated per request. (The maximum, 11, is for assets compressed
 * once ahead of time.)</p>
 */
public final class BrotliContentEncoder implements ContentEncoder {

    /** Compression level for dynamic content. */
    public static final int QUALITY = 4;

    private static final boolean AVAILABLE = load();

    private static boolean load() {
        try {
            Brotli4jLoader.ensureAvailability();
            return Brotli4jLoader.isAvailable();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static Encoder.Parameters parameters() {
        return new Encoder.Parameters().setQuality(QUALITY);
    }

    @Override
    public String encoding() {
        return "br";
    }

    @Override
    public int priority() {
        return 10; // ahead of gzip
    }

    @Override
    public boolean available() {
        return AVAILABLE;
    }

    @Override
    public byte[] encode(byte[] content) {
        try {
            return Encoder.compress(content, parameters());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public OutputStream wrap(OutputStream out) {
        try {
            return new BrotliOutputStream(out, parameters());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
