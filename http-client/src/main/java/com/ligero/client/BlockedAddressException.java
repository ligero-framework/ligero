package com.ligero.client;

/**
 * Thrown when a request would connect to an address its client's
 * {@link AddressFilter} refuses (an internal network, the cloud metadata
 * service, ...).
 */
public class BlockedAddressException extends RestClientException {

    private final String host;
    private final String address;

    public BlockedAddressException(String host, String address) {
        super("Refusing to connect to " + host + " (" + address + "): not an allowed address");
        this.host = host;
        this.address = address;
    }

    public String host() {
        return host;
    }

    public String address() {
        return address;
    }
}
