package com.ligero.compress.brotli;

import com.aayushatharva.brotli4j.decoder.BrotliInputStream;
import com.aayushatharva.brotli4j.decoder.Decoder;
import com.ligero.Ligero;
import com.ligero.config.LigeroConfig;
import com.ligero.server.pluma.PlumaServerEngine;
import com.ligero.spi.ContentEncoder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BrotliContentEncoderTest {

    private static final String TEXT = "{\"id\":1,\"name\":\"Teclado\",\"price\":19.9},".repeat(400);

    private final BrotliContentEncoder encoder = new BrotliContentEncoder();
    private final HttpClient client = HttpClient.newHttpClient();
    private Ligero app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void isDiscoveredAvailableAndPreferredOverGzip() {
        assertThat(ServiceLoader.load(ContentEncoder.class).stream().map(p -> p.type().getName()))
            .contains(BrotliContentEncoder.class.getName());
        assertThat(encoder.available()).isTrue();
        assertThat(encoder.encoding()).isEqualTo("br");
        assertThat(encoder.priority()).isPositive();
    }

    @Test
    void roundTripsAndBeatsGzip() throws Exception {
        byte[] original = TEXT.getBytes(StandardCharsets.UTF_8);
        byte[] compressed = encoder.encode(original);

        assertThat(Decoder.decompress(compressed).getDecompressedData()).isEqualTo(original);

        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
            gzip.write(original);
        }
        assertThat(compressed.length).isLessThan(gzipped.size()).isLessThan(original.length / 10);
    }

    @Test
    void streamingFlushDeliversWhatWasWrittenSoFar() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStream stream = encoder.wrap(sink);
        stream.write("first event\n".getBytes(StandardCharsets.UTF_8));
        stream.flush();
        assertThat(sink.size()).as("flush must not hold data back").isPositive();
        stream.write("second event\n".getBytes(StandardCharsets.UTF_8));
        stream.close();

        try (BrotliInputStream in = new BrotliInputStream(new ByteArrayInputStream(sink.toByteArray()))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("first event\nsecond event\n");
        }
    }

    @Test
    void thePlumaEngineServesBrotliWhenTheClientAcceptsIt() throws Exception {
        app = Ligero.create(LigeroConfig.builder().environment(Map.of()).host("127.0.0.1").port(0)
            .gzip(true).gzipMinBytes(100).build());
        app.engine(new PlumaServerEngine());
        app.get("/data", ctx -> ctx.res().contentType("application/json").send(TEXT));
        app.get("/stream", ctx -> {
            try (OutputStream out = ctx.res().contentType("text/plain").getOutputStream()) {
                out.write(TEXT.getBytes(StandardCharsets.UTF_8));
            }
        });
        app.start();
        String base = "http://127.0.0.1:" + app.port();

        for (String path : new String[] {"/data", "/stream"}) {
            HttpResponse<byte[]> brotli = client.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept-Encoding", "gzip, deflate, br").build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(brotli.headers().firstValue("Content-Encoding")).as(path).contains("br");
            assertThat(brotli.headers().allValues("Vary")).contains("Accept-Encoding");
            assertThat(new String(Decoder.decompress(brotli.body()).getDecompressedData(), StandardCharsets.UTF_8))
                .isEqualTo(TEXT);
        }

        // a client that refuses br (q=0) or does not know it still gets gzip
        HttpResponse<byte[]> gzipOnly = client.send(HttpRequest.newBuilder(URI.create(base + "/data"))
            .header("Accept-Encoding", "br;q=0, gzip").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(gzipOnly.headers().firstValue("Content-Encoding")).contains("gzip");

        HttpResponse<String> plain = client.send(HttpRequest.newBuilder(URI.create(base + "/data")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(plain.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(plain.body()).isEqualTo(TEXT);
    }
}
