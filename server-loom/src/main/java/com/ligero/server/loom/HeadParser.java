package com.ligero.server.loom;

import java.nio.charset.StandardCharsets;

/**
 * Strict HTTP/1.x request-head parser (RFC 9112). Anything ambiguous is
 * rejected rather than guessed at: two servers that disagree about where a
 * request ends is exactly what request smuggling exploits.
 */
final class HeadParser {

    private static final boolean[] TOKEN = new boolean[128];

    static {
        for (char c = '0'; c <= '9'; c++) {
            TOKEN[c] = true;
        }
        for (char c = 'a'; c <= 'z'; c++) {
            TOKEN[c] = true;
            TOKEN[Character.toUpperCase(c)] = true;
        }
        for (char c : "!#$%&'*+-.^_`|~".toCharArray()) {
            TOKEN[c] = true;
        }
    }

    private HeadParser() {
    }

    /** RFC 9110 {@code tchar}: what a method or header name may consist of. */
    static boolean isTokenChar(char c) {
        return c < 128 && TOKEN[c];
    }

    /**
     * Parses {@code buf[start, end)}, where {@code end} is just past the blank
     * line that terminates the head.
     */
    static RequestHead parse(byte[] buf, int start, int end, int maxHeaders) throws ProtocolException {
        int lineEnd = indexOfCrLf(buf, start, end);
        int sp1 = indexOf(buf, (byte) ' ', start, lineEnd);
        int sp2 = sp1 < 0 ? -1 : indexOf(buf, (byte) ' ', sp1 + 1, lineEnd);
        if (sp1 <= start || sp2 < 0 || sp2 == sp1 + 1 || indexOf(buf, (byte) ' ', sp2 + 1, lineEnd) >= 0) {
            throw bad();
        }
        String method = method(buf, start, sp1 - start);
        String target = target(buf, sp1 + 1, sp2 - sp1 - 1, method);
        boolean http10 = version(buf, sp2 + 1, lineEnd - sp2 - 1);

        String[] names = new String[16];
        String[] values = new String[16];
        int count = 0;
        long contentLength = -1;
        boolean chunked = false;
        boolean transferEncoding = false;
        boolean close = false;
        boolean connectionUpgrade = false;
        boolean upgradeWebSocket = false;
        boolean expectContinue = false;
        boolean acceptsGzip = false;
        int hosts = 0;

        int p = lineEnd + 2;
        while (p < end - 2) {
            int e = indexOfCrLf(buf, p, end);
            if (buf[p] == ' ' || buf[p] == '\t') {
                throw bad(); // obsolete line folding
            }
            int colon = indexOf(buf, (byte) ':', p, e);
            if (colon <= p) {
                throw bad();
            }
            for (int i = p; i < colon; i++) {
                byte b = buf[i];
                if (b < 0 || !TOKEN[b]) {
                    throw bad(); // also catches whitespace before the colon
                }
            }
            int valueStart = colon + 1;
            int valueEnd = e;
            while (valueStart < valueEnd && (buf[valueStart] == ' ' || buf[valueStart] == '\t')) {
                valueStart++;
            }
            while (valueEnd > valueStart && (buf[valueEnd - 1] == ' ' || buf[valueEnd - 1] == '\t')) {
                valueEnd--;
            }
            for (int i = valueStart; i < valueEnd; i++) {
                byte b = buf[i];
                if ((b >= 0 && b < 0x20 && b != '\t') || b == 0x7f) {
                    throw bad(); // control characters, including a bare CR or LF
                }
            }
            if (count == maxHeaders) {
                throw new ProtocolException(431, "Request Header Fields Too Large");
            }
            if (count == names.length) {
                names = java.util.Arrays.copyOf(names, count * 2);
                values = java.util.Arrays.copyOf(values, count * 2);
            }
            String name = new String(buf, p, colon - p, StandardCharsets.ISO_8859_1);
            String value = new String(buf, valueStart, valueEnd - valueStart, StandardCharsets.ISO_8859_1);
            names[count] = name;
            values[count] = value;
            count++;

            switch (name.length()) {
                case 4 -> {
                    if (name.equalsIgnoreCase("Host")) {
                        hosts++;
                    }
                }
                case 6 -> {
                    if (name.equalsIgnoreCase("Expect")) {
                        if (!value.equalsIgnoreCase("100-continue")) {
                            throw new ProtocolException(417, "Expectation Failed");
                        }
                        expectContinue = true;
                    }
                }
                case 7 -> {
                    if (name.equalsIgnoreCase("Upgrade")) {
                        upgradeWebSocket = value.equalsIgnoreCase("websocket");
                    }
                }
                case 10 -> {
                    if (name.equalsIgnoreCase("Connection")) {
                        for (String token : value.split(",")) {
                            String option = token.trim();
                            close |= option.equalsIgnoreCase("close");
                            connectionUpgrade |= option.equalsIgnoreCase("upgrade");
                        }
                    }
                }
                case 14 -> {
                    if (name.equalsIgnoreCase("Content-Length")) {
                        long length = contentLength(value);
                        if (contentLength >= 0 && contentLength != length) {
                            throw bad(); // conflicting lengths
                        }
                        contentLength = length;
                    }
                }
                case 15 -> {
                    if (name.equalsIgnoreCase("Accept-Encoding")) {
                        acceptsGzip |= value.contains("gzip");
                    }
                }
                case 17 -> {
                    if (name.equalsIgnoreCase("Transfer-Encoding")) {
                        if (transferEncoding) {
                            throw bad();
                        }
                        if (!value.equalsIgnoreCase("chunked")) {
                            throw new ProtocolException(501, "Not Implemented");
                        }
                        transferEncoding = true;
                        chunked = true;
                    }
                }
                default -> {
                    // not a header the engine itself interprets
                }
            }
            p = e + 2;
        }

        if (transferEncoding && (contentLength >= 0 || http10)) {
            throw bad(); // both framings at once is the classic smuggling vector
        }
        if (!http10 && hosts != 1) {
            throw bad();
        }
        if (hosts > 1) {
            throw bad();
        }
        boolean keepAlive = !http10 && !close;
        return new RequestHead(method, target, http10, names, values, count,
            Math.max(0, contentLength), chunked, keepAlive, expectContinue,
            upgradeWebSocket && connectionUpgrade, acceptsGzip);
    }

    private static ProtocolException bad() {
        return new ProtocolException(400, "Bad Request");
    }

    private static String method(byte[] buf, int offset, int length) throws ProtocolException {
        // the usual suspects, without allocating
        switch (length) {
            case 3 -> {
                if (matches(buf, offset, "GET")) {
                    return "GET";
                }
                if (matches(buf, offset, "PUT")) {
                    return "PUT";
                }
            }
            case 4 -> {
                if (matches(buf, offset, "POST")) {
                    return "POST";
                }
                if (matches(buf, offset, "HEAD")) {
                    return "HEAD";
                }
            }
            case 5 -> {
                if (matches(buf, offset, "PATCH")) {
                    return "PATCH";
                }
                if (matches(buf, offset, "QUERY")) {
                    return "QUERY";
                }
            }
            case 6 -> {
                if (matches(buf, offset, "DELETE")) {
                    return "DELETE";
                }
            }
            case 7 -> {
                if (matches(buf, offset, "OPTIONS")) {
                    return "OPTIONS";
                }
            }
            default -> {
                // validated below
            }
        }
        if (length > 32) {
            throw new ProtocolException(501, "Not Implemented");
        }
        for (int i = offset; i < offset + length; i++) {
            byte b = buf[i];
            if (b < 0 || !TOKEN[b]) {
                throw bad();
            }
        }
        return new String(buf, offset, length, StandardCharsets.US_ASCII).toUpperCase(java.util.Locale.ROOT);
    }

    private static String target(byte[] buf, int offset, int length, String method) throws ProtocolException {
        for (int i = offset; i < offset + length; i++) {
            byte b = buf[i];
            if (b <= 0x20 || b == 0x7f) {
                throw bad(); // includes bytes >= 0x80: targets must be percent-encoded ASCII
            }
        }
        if (buf[offset] == '/') {
            return new String(buf, offset, length, StandardCharsets.US_ASCII);
        }
        String raw = new String(buf, offset, length, StandardCharsets.US_ASCII);
        if (raw.equals("*") && method.equals("OPTIONS")) {
            return "/";
        }
        // absolute-form (sent to proxies): keep only path and query
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        int scheme = lower.startsWith("http://") ? 7 : lower.startsWith("https://") ? 8 : -1;
        if (scheme < 0) {
            throw bad();
        }
        int slash = raw.indexOf('/', scheme);
        int query = raw.indexOf('?', scheme);
        if (slash < 0 || (query >= 0 && query < slash)) {
            return query < 0 ? "/" : "/" + raw.substring(query);
        }
        return raw.substring(slash);
    }

    /** Returns true for HTTP/1.0, false for HTTP/1.1; anything else is refused. */
    private static boolean version(byte[] buf, int offset, int length) throws ProtocolException {
        if (length == 8 && matches(buf, offset, "HTTP/1.")) {
            if (buf[offset + 7] == '1') {
                return false;
            }
            if (buf[offset + 7] == '0') {
                return true;
            }
        }
        if (length >= 5 && matches(buf, offset, "HTTP/")) {
            throw new ProtocolException(505, "HTTP Version Not Supported");
        }
        throw bad();
    }

    /** Digits only — no sign, no whitespace, no list — and small enough to be a long. */
    private static long contentLength(String value) throws ProtocolException {
        int length = value.length();
        if (length == 0 || length > 18) {
            throw bad();
        }
        long result = 0;
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw bad();
            }
            result = result * 10 + (c - '0');
        }
        return result;
    }

    private static boolean matches(byte[] buf, int offset, String ascii) {
        for (int i = 0; i < ascii.length(); i++) {
            if (buf[offset + i] != ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] buf, byte value, int from, int to) {
        for (int i = from; i < to; i++) {
            if (buf[i] == value) {
                return i;
            }
        }
        return -1;
    }

    /** The head always ends in CRLF CRLF, so a line end is always found before {@code end}. */
    private static int indexOfCrLf(byte[] buf, int from, int end) {
        for (int i = from; i < end - 1; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n') {
                return i;
            }
        }
        return end - 2;
    }
}
