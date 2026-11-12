package com.ligero.server.loom;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The {@code Date} response header line, formatted once per second and shared. */
final class HttpDate {

    private static final DateTimeFormatter FORMAT =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    private record Cached(long second, byte[] line) {
    }

    private static volatile Cached cached = new Cached(-1, new byte[0]);

    private HttpDate() {
    }

    /** {@code "Date: <IMF-fixdate>\r\n"} for the current second. */
    static byte[] headerLine() {
        long second = System.currentTimeMillis() / 1000;
        Cached current = cached;
        if (current.second != second) {
            current = new Cached(second, ("Date: " + FORMAT.format(Instant.ofEpochSecond(second)) + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            cached = current;
        }
        return current.line;
    }
}
