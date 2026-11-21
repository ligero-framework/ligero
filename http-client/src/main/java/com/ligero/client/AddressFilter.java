package com.ligero.client;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Decides which addresses a {@link RestClient} may connect to — the defence
 * against server-side request forgery (SSRF), where a URL that came from a
 * user (a webhook, an avatar link, an import "from URL") points the
 * application at its own internal network instead: the cloud metadata
 * service at {@code 169.254.169.254}, an admin port on {@code localhost}, a
 * database on {@code 10.0.0.5}.
 *
 * <pre>{@code
 * RestClient webhooks = RestClient.builder()
 *     .addressFilter(AddressFilter.publicOnly())   // the whole internet, nothing internal
 *     .build();
 * }</pre>
 *
 * <p>{@link #publicOnly()} refuses loopback, private (RFC 1918, IPv6 unique
 * local), link-local (which includes cloud metadata endpoints), carrier-grade
 * NAT, multicast, broadcast and unspecified addresses, plus the IPv6 forms
 * that embed them (IPv4-mapped, NAT64, 6to4). Every address a host name
 * resolves to is checked, and so is every redirect.</p>
 *
 * <p>The check resolves the host name and then lets the JDK client connect,
 * which resolves it again: a DNS server that answers differently the second
 * time (DNS rebinding) could slip through. For URLs that come straight from
 * untrusted users, also restrict the egress at the network level.</p>
 */
public final class AddressFilter {

    private record Network(byte[] address, int prefixBits) {

        static Network parse(String cidr) {
            String text = cidr.trim();
            int slash = text.indexOf('/');
            byte[] address = literal(slash < 0 ? text : text.substring(0, slash));
            if (address == null) {
                throw new IllegalArgumentException("Not an IP address or CIDR range: " + cidr);
            }
            int bits = address.length * 8;
            if (slash >= 0) {
                try {
                    bits = Integer.parseInt(text.substring(slash + 1));
                } catch (NumberFormatException e) {
                    bits = -1;
                }
                if (bits < 0 || bits > address.length * 8) {
                    throw new IllegalArgumentException("Invalid prefix length in " + cidr);
                }
            }
            return new Network(address, bits);
        }

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

    /** Ranges no public service lives in. */
    private static final List<Network> NON_PUBLIC = List.of(
        Network.parse("0.0.0.0/8"),          // "this network", unspecified
        Network.parse("10.0.0.0/8"),         // private
        Network.parse("100.64.0.0/10"),      // carrier-grade NAT
        Network.parse("127.0.0.0/8"),        // loopback
        Network.parse("169.254.0.0/16"),     // link-local, cloud metadata
        Network.parse("172.16.0.0/12"),      // private
        Network.parse("192.0.0.0/24"),       // IETF protocol assignments
        Network.parse("192.0.2.0/24"),       // documentation
        Network.parse("192.88.99.0/24"),     // 6to4 relay anycast
        Network.parse("192.168.0.0/16"),     // private
        Network.parse("198.18.0.0/15"),      // benchmarking
        Network.parse("198.51.100.0/24"),    // documentation
        Network.parse("203.0.113.0/24"),     // documentation
        Network.parse("224.0.0.0/4"),        // multicast
        Network.parse("240.0.0.0/4"),        // reserved, broadcast
        Network.parse("::/128"),             // unspecified
        Network.parse("::1/128"),            // loopback
        Network.parse("64:ff9b:1::/48"),     // local-use NAT64
        Network.parse("100::/64"),           // discard
        Network.parse("2001:db8::/32"),      // documentation
        Network.parse("fc00::/7"),           // unique local
        Network.parse("fe80::/10"),          // link-local
        Network.parse("ff00::/8"));          // multicast

    private final boolean publicOnly;
    private final List<Network> allowed;
    private final List<Network> denied;

    private AddressFilter(boolean publicOnly, List<Network> allowed, List<Network> denied) {
        this.publicOnly = publicOnly;
        this.allowed = List.copyOf(allowed);
        this.denied = List.copyOf(denied);
    }

    /** Any address (the default of a client that only calls services you configured yourself). */
    public static AddressFilter allowAll() {
        return new AddressFilter(false, List.of(), List.of());
    }

    /** Only public internet addresses; see the class description. */
    public static AddressFilter publicOnly() {
        return new AddressFilter(true, List.of(), List.of());
    }

    /** A copy that also allows {@code cidrs}, e.g. one internal service {@code "10.20.0.7"}. */
    public AddressFilter allow(String... cidrs) {
        List<Network> more = new ArrayList<>(allowed);
        for (String cidr : cidrs) {
            more.add(Network.parse(cidr));
        }
        return new AddressFilter(publicOnly, more, denied);
    }

    /** A copy that also refuses {@code cidrs}. Refusals win over {@link #allow}. */
    public AddressFilter deny(String... cidrs) {
        List<Network> more = new ArrayList<>(denied);
        for (String cidr : cidrs) {
            more.add(Network.parse(cidr));
        }
        return new AddressFilter(publicOnly, allowed, more);
    }

    /** True when this filter checks anything at all. */
    boolean isActive() {
        return publicOnly || !denied.isEmpty();
    }

    /**
     * Resolves {@code host} and checks every address it has.
     *
     * @throws BlockedAddressException if one of them is not allowed
     * @throws UnknownHostException    if it does not resolve
     */
    public void check(String host) throws UnknownHostException {
        Objects.requireNonNull(host, "host");
        String name = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        for (InetAddress address : InetAddress.getAllByName(name)) {
            if (!permits(address.getAddress())) {
                throw new BlockedAddressException(host, address.getHostAddress());
            }
        }
    }

    /** Whether a connection to {@code address} is allowed. */
    public boolean permits(InetAddress address) {
        return permits(address.getAddress());
    }

    private boolean permits(byte[] raw) {
        byte[] address = embeddedIpv4(raw);
        for (Network network : denied) {
            if (network.contains(address) || network.contains(raw)) {
                return false;
            }
        }
        if (!publicOnly) {
            return true;
        }
        for (Network network : allowed) {
            if (network.contains(address) || network.contains(raw)) {
                return true;
            }
        }
        for (Network network : NON_PUBLIC) {
            if (network.contains(address) || network.contains(raw)) {
                return false;
            }
        }
        return true;
    }

    /**
     * IPv6 addresses that carry an IPv4 address are judged by it:
     * IPv4-mapped ({@code ::ffff:10.0.0.1}), IPv4-compatible, NAT64
     * ({@code 64:ff9b::/96}) and 6to4 ({@code 2002::/16}).
     */
    private static byte[] embeddedIpv4(byte[] address) {
        if (address.length != 16) {
            return address;
        }
        boolean firstTenZero = true;
        for (int i = 0; i < 10; i++) {
            firstTenZero &= address[i] == 0;
        }
        boolean mapped = firstTenZero && (address[10] & 0xff) == 0xff && (address[11] & 0xff) == 0xff;
        boolean compatible = firstTenZero && address[10] == 0 && address[11] == 0
            && !(address[12] == 0 && address[13] == 0 && address[14] == 0 && (address[15] == 0 || address[15] == 1));
        boolean nat64 = (address[0] & 0xff) == 0x00 && (address[1] & 0xff) == 0x64
            && (address[2] & 0xff) == 0xff && (address[3] & 0xff) == 0x9b
            && address[4] == 0 && address[5] == 0 && address[6] == 0 && address[7] == 0
            && address[8] == 0 && address[9] == 0 && address[10] == 0 && address[11] == 0;
        if (mapped || compatible || nat64) {
            return new byte[] {address[12], address[13], address[14], address[15]};
        }
        if ((address[0] & 0xff) == 0x20 && (address[1] & 0xff) == 0x02) { // 6to4: 2002:AABB:CCDD::/48
            return new byte[] {address[2], address[3], address[4], address[5]};
        }
        return address;
    }

    /** Parses an IP literal without any DNS lookup, or returns {@code null}. */
    private static byte[] literal(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > 45 || !value.matches("[0-9a-f:.]+")) {
            return null;
        }
        if (!value.contains(":") && !value.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
