package com.ligero.server.pluma;

import com.ligero.spi.ContentEncoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.zip.GZIPOutputStream;

/**
 * Chooses how to compress a response: gzip is built in, further codecs
 * (Brotli) come from the {@link ContentEncoder} SPI. Only content that
 * actually shrinks is compressed — text, JSON, XML, JavaScript, SVG — never
 * images, video or archives that are compressed already.
 */
final class Compression {

    /** gzip from the JDK; {@code flush()} emits a sync block so streamed output is not held back. */
    private static final ContentEncoder GZIP = new ContentEncoder() {
        @Override
        public String encoding() {
            return "gzip";
        }

        @Override
        public byte[] encode(byte[] content) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(content.length / 2 + 16);
            try (GZIPOutputStream out = new GZIPOutputStream(buffer)) {
                out.write(content);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return buffer.toByteArray();
        }

        @Override
        public OutputStream wrap(OutputStream out) {
            try {
                return new GZIPOutputStream(out, 8 * 1024, true);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    };

    /** Highest priority first. */
    private final List<ContentEncoder> encoders;

    private Compression(List<ContentEncoder> encoders) {
        this.encoders = encoders;
    }

    /** gzip plus every available {@link ContentEncoder} on the classpath. */
    static Compression load() {
        List<ContentEncoder> found = new ArrayList<>();
        found.add(GZIP);
        for (ContentEncoder encoder : ServiceLoader.load(ContentEncoder.class)) {
            if (encoder.available()) {
                found.add(encoder);
            }
        }
        found.sort(Comparator.comparingInt(ContentEncoder::priority).reversed());
        return new Compression(List.copyOf(found));
    }

    /**
     * The encoder to use for a response of {@code contentType} to a client
     * that sent {@code acceptEncoding}, or {@code null} for no compression.
     */
    ContentEncoder choose(String acceptEncoding, String contentType) {
        if (acceptEncoding == null || acceptEncoding.isEmpty() || !isCompressible(contentType)) {
            return null;
        }
        String accepted = acceptEncoding.toLowerCase(Locale.ROOT);
        for (ContentEncoder encoder : encoders) {
            if (accepts(accepted, encoder.encoding())) {
                return encoder;
            }
        }
        return null;
    }

    /** True when the header lists {@code encoding} with a non-zero quality. */
    static boolean accepts(String acceptEncodingLowerCase, String encoding) {
        for (String part : acceptEncodingLowerCase.split(",")) {
            String item = part.trim();
            int semicolon = item.indexOf(';');
            String name = semicolon < 0 ? item : item.substring(0, semicolon).trim();
            if (!name.equals(encoding)) {
                continue;
            }
            if (semicolon < 0) {
                return true;
            }
            String parameter = item.substring(semicolon + 1).replace(" ", "");
            if (!parameter.startsWith("q=")) {
                return true;
            }
            try {
                return Double.parseDouble(parameter.substring(2)) > 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    static boolean isCompressible(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        int semicolon = type.indexOf(';');
        if (semicolon >= 0) {
            type = type.substring(0, semicolon).trim();
        }
        return type.startsWith("text/")
            || type.endsWith("+json") || type.endsWith("+xml")
            || type.equals("application/json") || type.equals("application/xml")
            || type.equals("application/javascript") || type.equals("application/x-javascript")
            || type.equals("application/x-www-form-urlencoded") || type.equals("application/wasm");
    }
}
