package com.ligero.server.loom;

import java.io.IOException;

/** A request the engine refuses at the protocol level, with the status to answer. */
final class ProtocolException extends IOException {

    private final int status;

    ProtocolException(int status, String reason) {
        super(reason);
        this.status = status;
    }

    int status() {
        return status;
    }
}
