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

    private final List<Network> trusted;

    private TrustedProxyMiddleware(List<Network> trusted) {
        this.trusted = trusted;
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
        return new TrustedProxyMiddleware(List.copyOf(networks));
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        String peer = ctx.req().getRemoteAddress();
        if (isTrusted(peer)) {
            String client = clientFrom(ctx.header("X-Forwarded-For"));
            if (client != null) {
                ctx.attribute(Context.CLIENT_ADDRESS_ATTRIBUTE, client);
            }
        }
        chain.proceed();
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
