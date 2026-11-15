package com.ligero.server.pluma;

import com.ligero.spi.EngineConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Locale;

/** A response sent as HTTP/2 HEADERS and DATA frames. */
final class Http2Response extends AbstractResponse {

    private final Http2Connection connection;
    private final Http2Stream stream;

    Http2Response(Http2Connection connection, Http2Stream stream, EngineConfig config, Compression compression) {
        super(config, compression, "HEAD".equals(stream.head().method));
        this.connection = connection;
        this.stream = stream;
    }

    @Override
    String acceptEncoding() {
        return stream.head().header("accept-encoding");
    }

    @Override
    void closeRequested() {
        // "Connection: close" has no meaning for a single HTTP/2 stream
    }

    @Override
    void commit(byte[] body, String contentEncoding) {
        boolean sendBody = body != null && !isHeadRequest();
        int length = body == null ? 0 : body.length;
        connection.sendHeaders(stream, encoder -> fields(encoder, contentEncoding, body != null, length), !sendBody);
        if (sendBody) {
            try {
                connection.sendData(stream, body, 0, length, true);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not send response", e);
            }
        }
        stream.responseEnded();
    }

    @Override
    OutputStream commitStreaming(String contentEncoding) {
        boolean noBody = isHeadRequest() || !hasBody();
        connection.sendHeaders(stream, encoder -> fields(encoder, contentEncoding, true, -1), noBody);
        if (noBody) {
            stream.responseEnded();
        }
        return new Http2Connection.DataOutputStream(connection, stream);
    }

    /** {@code contentLength < 0} means "streamed, length unknown". */
    private void fields(Hpack.Encoder encoder, String contentEncoding, boolean withContentType, int contentLength) {
        encoder.status(status);
        encoder.field("date", HttpDate.value());
        for (int i = 0; i < headerCount; i++) {
            String name = headerNames[i].toLowerCase(Locale.ROOT);
            // hop-by-hop fields do not exist in HTTP/2
            if (name.equals("keep-alive") || name.equals("proxy-connection") || name.equals("upgrade")) {
                continue;
            }
            encoder.field(name, headerValues[i]);
        }
        if (contentEncoding != null) {
            encoder.field("content-encoding", contentEncoding);
            encoder.field("vary", "Accept-Encoding");
        }
        if (hasBody()) {
            if (withContentType) {
                encoder.field("content-type", contentType);
            }
            if (contentLength >= 0) {
                encoder.field("content-length", Integer.toString(contentLength));
            }
        }
    }
}
