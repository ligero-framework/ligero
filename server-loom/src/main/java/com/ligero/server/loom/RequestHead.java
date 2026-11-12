package com.ligero.server.loom;

/** A parsed request line and header block. */
final class RequestHead {

    final String method;
    /** Request target as received: path plus optional query string. */
    final String target;
    final boolean http10;
    final String[] names;
    final String[] values;
    final int headerCount;

    final long contentLength;      // 0 when absent
    final boolean chunked;
    final boolean keepAlive;
    final boolean expectContinue;
    final boolean webSocketUpgrade;
    final boolean acceptsGzip;

    RequestHead(String method, String target, boolean http10, String[] names, String[] values, int headerCount,
                long contentLength, boolean chunked, boolean keepAlive, boolean expectContinue,
                boolean webSocketUpgrade, boolean acceptsGzip) {
        this.method = method;
        this.target = target;
        this.http10 = http10;
        this.names = names;
        this.values = values;
        this.headerCount = headerCount;
        this.contentLength = contentLength;
        this.chunked = chunked;
        this.keepAlive = keepAlive;
        this.expectContinue = expectContinue;
        this.webSocketUpgrade = webSocketUpgrade;
        this.acceptsGzip = acceptsGzip;
    }

    /** First value of a header (case-insensitive), or {@code null}. */
    String header(String name) {
        for (int i = 0; i < headerCount; i++) {
            if (names[i].equalsIgnoreCase(name)) {
                return values[i];
            }
        }
        return null;
    }

    /** The target without its query string. */
    String path() {
        int query = target.indexOf('?');
        return query < 0 ? target : target.substring(0, query);
    }
}
