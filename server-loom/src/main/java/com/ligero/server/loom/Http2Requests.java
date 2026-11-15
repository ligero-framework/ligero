package com.ligero.server.loom;

import java.util.List;

/**
 * Turns a decoded HTTP/2 header list into a request, enforcing the rules of
 * RFC 9113 §8: a request that breaks any of them is malformed and is refused
 * rather than repaired, because a lenient translation to "what was probably
 * meant" is how HTTP/2 front doors smuggle requests into HTTP/1 semantics.
 */
final class Http2Requests {

    private Http2Requests() {
    }

    /** The request described by {@code fields}, or {@code null} when it is malformed. */
    static RequestHead toRequestHead(List<Hpack.Field> fields, String connectionScheme) {
        String method = null;
        String path = null;
        String scheme = null;
        String authority = null;
        boolean regularSeen = false;
        boolean hostSeen = false;
        String[] names = new String[fields.size() + 1];
        String[] values = new String[fields.size() + 1];
        int count = 0;
        int cookieIndex = -1;

        for (Hpack.Field field : fields) {
            String name = field.name();
            String value = field.value();
            if (name.isEmpty() || !validValue(value)) {
                return null;
            }
            if (name.charAt(0) == ':') {
                if (regularSeen) {
                    return null; // pseudo-headers come first
                }
                switch (name) {
                    case ":method" -> {
                        if (method != null) {
                            return null;
                        }
                        method = value;
                    }
                    case ":path" -> {
                        if (path != null) {
                            return null;
                        }
                        path = value;
                    }
                    case ":scheme" -> {
                        if (scheme != null) {
                            return null;
                        }
                        scheme = value;
                    }
                    case ":authority" -> {
                        if (authority != null) {
                            return null;
                        }
                        authority = value;
                    }
                    default -> {
                        return null; // unknown or response-only pseudo-header
                    }
                }
                continue;
            }
            regularSeen = true;
            if (!validName(name)) {
                return null; // upper case, or not a token
            }
            switch (name) {
                // connection-specific fields have no meaning in HTTP/2 and must not appear
                case "connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade" -> {
                    return null;
                }
                case "te" -> {
                    if (!value.equals("trailers")) {
                        return null;
                    }
                }
                case "host" -> hostSeen = true;
                case "cookie" -> {
                    // clients may split the cookie header into one field per cookie (RFC 9113 §8.2.3)
                    if (cookieIndex >= 0) {
                        values[cookieIndex] = values[cookieIndex] + "; " + value;
                        continue;
                    }
                    cookieIndex = count;
                }
                default -> {
                    // an ordinary header
                }
            }
            names[count] = name;
            values[count] = value;
            count++;
        }

        if (method == null || path == null || scheme == null || !validToken(method)
                || method.equals("CONNECT")) {
            return null;
        }
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        if (path.equals("*") && method.equals("OPTIONS")) {
            path = "/";
        }
        if (path.isEmpty() || path.charAt(0) != '/') {
            return null;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 0x20 || c >= 0x7f) {
                return null; // same rule as HTTP/1.1: targets are percent-encoded ASCII
            }
        }
        long contentLength = contentLength(fields);
        if (contentLength == -2) {
            return null;
        }
        if (!hostSeen && authority != null) {
            names[count] = "host"; // what the application calls Host is :authority here
            values[count] = authority;
            count++;
        }
        return new RequestHead(method, path, false, names, values, count,
            Math.max(0, contentLength), false, true, false, false);
    }

    /** The declared body length, -1 when there is none, -2 when it is malformed or contradictory. */
    static long contentLength(List<Hpack.Field> fields) {
        long length = -1;
        for (Hpack.Field field : fields) {
            if (!field.name().equals("content-length")) {
                continue;
            }
            String value = field.value();
            if (value.isEmpty() || value.length() > 18) {
                return -2;
            }
            long parsed = 0;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < '0' || c > '9') {
                    return -2;
                }
                parsed = parsed * 10 + (c - '0');
            }
            if (length >= 0 && length != parsed) {
                return -2;
            }
            length = parsed;
        }
        return length;
    }

    /** Lower-case token characters only: HTTP/2 field names are never upper case. */
    private static boolean validName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'A' && c <= 'Z') || !HeadParser.isTokenChar(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validToken(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!HeadParser.isTokenChar(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** No control characters, and no leading or trailing whitespace. */
    private static boolean validValue(String value) {
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f) {
                return false;
            }
        }
        return length == 0 || (!isSpace(value.charAt(0)) && !isSpace(value.charAt(length - 1)));
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }
}
