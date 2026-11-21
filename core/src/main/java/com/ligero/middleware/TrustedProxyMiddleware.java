package com.ligero.middleware;

import com.ligero.http.Context;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes {@code ctx.remoteAddress()} return the real client address when the
 * application runs behind reverse proxies or load balancers.
 *
 * <p>Behind a proxy every connection comes from the proxy, so rate limits and
 * access logs keyed on the remote address see a single "client". The proxy
 * passes the original address in {@code X-Forwarded-For} — but so can anyone
 * else: a client talking to the application directly can send that header
 * with whatever address it likes. The header is therefore only believed when
 * the connection itself comes from a proxy you listed, and it is read from
 * the right: each proxy appends the address it saw, so the last entry that is
 * <em>not</em> one of your proxies is the client, and anything further left
 * was supplied by the client and is ignored.</p>
 *
 * <pre>{@code
 * // install first, so every later middleware sees the client address
 * app.use(TrustedProxyMiddleware.of("10.0.0.0/8", "192.168.1.10"));
 * app.use(RateLimitMiddleware.of(100, 100));
 * }</pre>
 *
 * <p>Proxies that send the standard {@code Forwarded} header
 * (<a href="https://www.rfc-editor.org/rfc/rfc7239">RFC 7239</a>) instead —
 * {@code Forwarded: for=203.0.113.7;proto=https;host=shop.example} — are
 * read with {@link #withForwardedHeader()}. Pick the header your proxy
 * actually writes: the other one arrives untouched from the client, so
 * reading it would let the client choose its own address. In that mode the
 * original scheme and host of the request are also available, as the
 * {@link #PROTO_ATTRIBUTE} and {@link #HOST_ATTRIBUTE} context attributes.</p>
 */
public final class TrustedProxyMiddleware implements Middleware {

    private record Network(byte[] address, int prefixBits) {

        boolean contains(byte[] candidate) {
            if (candidate.length != address.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != address[i]) {
                    return false;
                }
            }
            int remaining = prefixBits % 8;
            if (remaining == 0) {
                return true;
            }
            int mask = 0xff << (8 - remaining) & 0xff;
            return (candidate[fullBytes] & mask) == (address[fullBytes] & mask);
        }
    }

    /** Context attribute: the scheme the client used ({@code http} / {@code https}), from {@code Forwarded}. */
    public static final String PROTO_ATTRIBUTE = "ligero.forwarded.proto";

    /** Context attribute: the {@code Host} the client asked for, from {@code Forwarded}. */
    public static final String HOST_ATTRIBUTE = "ligero.forwarded.host";

    /** One element of a {@code Forwarded} header (one proxy hop). */
    private record Hop(String forValue, String proto, String host) {
    }

    private final List<Network> trusted;
    private final boolean forwardedHeader;

    private TrustedProxyMiddleware(List<Network> trusted, boolean forwardedHeader) {
        this.trusted = trusted;
        this.forwardedHeader = forwardedHeader;
    }

    /**
     * The same proxies, read from the standard {@code Forwarded} header
     * instead of {@code X-Forwarded-For}.
     */
    public TrustedProxyMiddleware withForwardedHeader() {
        return new TrustedProxyMiddleware(trusted, true);
    }

    /**
     * @param proxies addresses or CIDR ranges of your own proxies, e.g.
     *                {@code "10.0.0.0/8"}, {@code "192.168.1.10"}, {@code "::1"}
     * @throws IllegalArgumentException if an entry is not an IP address or range
     */
    public static TrustedProxyMiddleware of(String... proxies) {
        if (proxies.length == 0) {
            throw new IllegalArgumentException("List at least one trusted proxy address or range");
        }
        List<Network> networks = new ArrayList<>();
        for (String proxy : proxies) {
            String text = proxy.trim();
            int slash = text.indexOf('/');
            byte[] address = parse(slash < 0 ? text : text.substring(0, slash));
            if (address == null) {
                throw new IllegalArgumentException("Not an IP address or CIDR range: " + proxy);
            }
            int bits = address.length * 8;
            if (slash >= 0) {
                try {
                    bits = Integer.parseInt(text.substring(slash + 1));
                } catch (NumberFormatException e) {
                    bits = -1;
                }
                if (bits < 0 || bits > address.length * 8) {
                    throw new IllegalArgumentException("Invalid prefix length in " + proxy);
                }
            }
            networks.add(new Network(address, bits));
        }
        return new TrustedProxyMiddleware(List.copyOf(networks), false);
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        String peer = ctx.req().getRemoteAddress();
        if (isTrusted(peer)) {
            if (forwardedHeader) {
                applyForwarded(ctx);
            } else {
                String client = clientFrom(ctx.header("X-Forwarded-For"));
                if (client != null) {
                    ctx.attribute(Context.CLIENT_ADDRESS_ATTRIBUTE, client);
                }
            }
        }
        chain.proceed();
    }

    private void applyForwarded(Context ctx) {
        List<String> values = ctx.req().getHeaders().entrySet().stream()
            .filter(header -> header.getKey().equalsIgnoreCase("Forwarded"))
            .flatMap(header -> header.getValue().stream())
            .toList();
        if (values.isEmpty()) {
            return;
        }
        List<Hop> hops = parseForwarded(String.join(",", values));
        if (hops == null) {
            return; // malformed: believe nothing
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            Hop hop = hops.get(i);
            String address = nodeAddress(hop.forValue());
            if (address == null) {
                return; // obfuscated, unknown or missing: nothing further left can be trusted
            }
            if (!isTrusted(parse(address))) {
                ctx.attribute(Context.CLIENT_ADDRESS_ATTRIBUTE, address);
                // the element that names the client was written by the first of our proxies,
                // so its proto and host describe the request the client actually sent
                if (hop.proto() != null && (hop.proto().equalsIgnoreCase("http") || hop.proto().equalsIgnoreCase("https"))) {
                    ctx.attribute(PROTO_ATTRIBUTE, hop.proto().toLowerCase(java.util.Locale.ROOT));
                }
                if (hop.host() != null && hop.host().matches("[A-Za-z0-9.\\-]{1,253}(:[0-9]{1,5})?|\\[[0-9A-Fa-f:.]{2,45}\\](:[0-9]{1,5})?")) {
                    ctx.attribute(HOST_ATTRIBUTE, hop.host());
                }
                return;
            }
        }
    }

    /**
     * Splits a {@code Forwarded} value into hops ({@code ,}) of
     * {@code name=value} pairs ({@code ;}), honouring quoted strings. Returns
     * {@code null} when the value is malformed.
     */
    static List<Hop> parseForwarded(String header) {
        if (header.length() > 8192) {
            return null;
        }
        List<Hop> hops = new ArrayList<>();
        String forValue = null;
        String proto = null;
        String host = null;
        int i = 0;
        int n = header.length();
        while (i <= n) {
            while (i < n && (header.charAt(i) == ' ' || header.charAt(i) == '\t')) {
                i++;
            }
            if (i == n || header.charAt(i) == ',') { // end of a hop (empty hops are tolerated)
                if (forValue != null || proto != null || host != null) {
                    hops.add(new Hop(forValue, proto, host));
                }
                forValue = proto = host = null;
                i++;
                continue;
            }
            if (header.charAt(i) == ';') {
                i++;
                continue;
            }
            int eq = header.indexOf('=', i);
            if (eq < 0) {
                return null;
            }
            String name = header.substring(i, eq).trim().toLowerCase(java.util.Locale.ROOT);
            if (!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+")) {
                return null;
            }
            i = eq + 1;
            String value;
            if (i < n && header.charAt(i) == '"') {
                StringBuilder quoted = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char c = header.charAt(i++);
                    if (c == '\\' && i < n) {
                        quoted.append(header.charAt(i++));
                    } else if (c == '"') {
                        closed = true;
                        break;
                    } else {
                        quoted.append(c);
                    }
                }
                if (!closed) {
                    return null;
                }
                value = quoted.toString();
            } else {
                int end = i;
                while (end < n && header.charAt(end) != ';' && header.charAt(end) != ',') {
                    end++;
                }
                value = header.substring(i, end).trim();
                i = end;
            }
            switch (name) {
                case "for" -> {
                    if (forValue != null) {
                        return null; // a parameter may appear only once per hop
                    }
                    forValue = value;
                }
                case "proto" -> proto = value;
                case "host" -> host = value;
                default -> {
                    // "by" and extensions are not needed
                }
            }
        }
        return hops;
    }

    /** The IP of a {@code for=} node ({@code 192.0.2.1}, {@code 192.0.2.1:80}, {@code [2001:db8::1]:80}). */
    private static String nodeAddress(String node) {
        if (node == null || node.isEmpty()) {
            return null;
        }
        String address = node;
        if (address.startsWith("[")) {
            int close = address.indexOf(']');
            if (close < 0) {
                return null;
            }
            address = address.substring(1, close);
        } else if (address.indexOf(':') == address.lastIndexOf(':') && address.indexOf(':') >= 0) {
            address = address.substring(0, address.indexOf(':')); // IPv4 with a port
        }
        return parse(address) == null ? null : address;
    }

    /** The right-most address in the header that is not one of our proxies. */
    private String clientFrom(String forwardedFor) {
        if (forwardedFor == null) {
            return null;
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            byte[] address = parse(hop);
            if (address == null) {
                return null; // not an address: stop rather than trust what lies beyond it
            }
            if (!isTrusted(address)) {
                return hop;
            }
        }
        return null;
    }

    private boolean isTrusted(String address) {
        byte[] bytes = address == null ? null : parse(address);
        return bytes != null && isTrusted(bytes);
    }

    private boolean isTrusted(byte[] address) {
        for (Network network : trusted) {
            if (network.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /** Parses an IP literal; returns {@code null} for anything else (host names are never resolved). */
    private static byte[] parse(String text) {
        String literal = text.startsWith("[") && text.endsWith("]") ? text.substring(1, text.length() - 1) : text;
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        if (literal.isEmpty() || literal.length() > 45 || !literal.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        try {
            byte[] address = InetAddress.getByName(literal).getAddress();
            // an IPv4-mapped IPv6 address is the IPv4 address
            return address;
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
