package com.ligero.server.loom;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HPACK against the worked examples of RFC 7541 Appendix C, plus the ways a block can be hostile. */
class HpackTest {

    private static byte[] hex(String hex) {
        String clean = hex.replace(" ", "");
        byte[] bytes = new byte[clean.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(clean.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    private static List<Hpack.Field> decode(Hpack.Decoder decoder, String hex) throws Exception {
        byte[] block = hex(hex);
        return decoder.decode(block, 0, block.length);
    }

    @Test
    void decodesTheRfcRequestExamplesWithoutHuffman() throws Exception {
        Hpack.Decoder decoder = new Hpack.Decoder(4096, 16 * 1024);

        // C.3.1
        assertThat(decode(decoder, "8286 8441 0f77 7777 2e65 7861 6d70 6c65 2e63 6f6d")).containsExactly(
            new Hpack.Field(":method", "GET"), new Hpack.Field(":scheme", "http"),
            new Hpack.Field(":path", "/"), new Hpack.Field(":authority", "www.example.com"));
        // C.3.2 — refers to the dynamic table entry added by the first request (index 62)
        assertThat(decode(decoder, "8286 84be 5808 6e6f 2d63 6163 6865")).containsExactly(
            new Hpack.Field(":method", "GET"), new Hpack.Field(":scheme", "http"),
            new Hpack.Field(":path", "/"), new Hpack.Field(":authority", "www.example.com"),
            new Hpack.Field("cache-control", "no-cache"));
        // C.3.3
        assertThat(decode(decoder, "8287 85bf 400a 6375 7374 6f6d 2d6b 6579 0c63 7573 746f 6d2d 7661 6c75 65"))
            .containsExactly(
                new Hpack.Field(":method", "GET"), new Hpack.Field(":scheme", "https"),
                new Hpack.Field(":path", "/index.html"), new Hpack.Field(":authority", "www.example.com"),
                new Hpack.Field("custom-key", "custom-value"));
    }

    @Test
    void decodesTheRfcRequestExamplesWithHuffman() throws Exception {
        Hpack.Decoder decoder = new Hpack.Decoder(4096, 16 * 1024);

        // C.4.1
        assertThat(decode(decoder, "8286 8441 8cf1 e3c2 e5f2 3a6b a0ab 90f4 ff")).containsExactly(
            new Hpack.Field(":method", "GET"), new Hpack.Field(":scheme", "http"),
            new Hpack.Field(":path", "/"), new Hpack.Field(":authority", "www.example.com"));
        // C.4.2
        assertThat(decode(decoder, "8286 84be 5886 a8eb 1064 9cbf")).last()
            .isEqualTo(new Hpack.Field("cache-control", "no-cache"));
        // C.4.3
        assertThat(decode(decoder, "8287 85bf 4088 25a8 49e9 5ba9 7d7f 8925 a849 e95b b8e8 b4bf")).last()
            .isEqualTo(new Hpack.Field("custom-key", "custom-value"));
    }

    @Test
    void huffmanRoundTripsEveryByteValue() throws Exception {
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            all.append((char) i);
        }
        byte[] encoded = Hpack.huffmanEncode(all.toString());
        assertThat(Hpack.huffmanDecode(encoded, 0, encoded.length, 1024)).isEqualTo(all.toString());

        // RFC 7541 C.4.1: "www.example.com"
        assertThat(Hpack.huffmanEncode("www.example.com")).isEqualTo(hex("f1e3 c2e5 f23a 6ba0 ab90 f4ff"));
    }

    @Test
    void evictsFromTheDynamicTableAndHonoursSizeUpdates() throws Exception {
        // C.5: a 256-byte table; the third response evicts the earlier entries
        Hpack.Decoder decoder = new Hpack.Decoder(256, 16 * 1024);
        decode(decoder, "4803 3330 3258 0770 7269 7661 7465 611d 4d6f 6e2c 2032 3120 4f63 7420 3230 3133 "
            + "2032 303a 3133 3a32 3120 474d 546e 1768 7474 7073 3a2f 2f77 7777 2e65 7861 6d70 6c65 2e63 6f6d");
        assertThat(decode(decoder, "4803 3330 37c1 c0bf")).first().isEqualTo(new Hpack.Field(":status", "307"));

        // a size update is only legal at the start of a block and within the advertised maximum
        assertThat(decode(new Hpack.Decoder(4096, 1024), "20 82")).containsExactly(new Hpack.Field(":method", "GET"));
        assertThatThrownBy(() -> decode(new Hpack.Decoder(4096, 1024), "82 20"))
            .isInstanceOf(Hpack.HpackException.class);
        assertThatThrownBy(() -> decode(new Hpack.Decoder(4096, 1024), "3f e1 ff 03"))
            .isInstanceOf(Hpack.HpackException.class).hasMessageContaining("maximum");
    }

    @Test
    void hostileBlocksAreErrorsNotCrashes() {
        String[] hostile = {
            "80",                       // index 0 does not exist
            "ff ff ff ff ff ff ff 7f",  // an integer that never fits
            "be",                       // index 62 with an empty dynamic table
            "00 05 61",                 // literal name longer than the block
            "82 86 84 41 8c f1",        // Huffman string cut short
            "00 81 ff 01 61",           // Huffman padding that is not a prefix of EOS (a full byte of ones decodes nothing)
            "00 82 ff ff 01 61",        // more than 7 bits of padding
            "00 84 ff ff ff ff 01 61",  // the EOS symbol itself
        };
        for (String block : hostile) {
            assertThatThrownBy(() -> decode(new Hpack.Decoder(4096, 1024), block))
                .as(block).isInstanceOf(Hpack.HpackException.class);
        }
    }

    @Test
    void headerListsAreBoundedWhileDecoding() {
        // "decompression bomb": one small indexed reference repeated expands far beyond its wire size
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(0x40);                                  // literal with indexing, new name
        block.write(1);
        block.write('a');
        block.write(0x7f);                                  // value length 127 + ...
        block.write(0x80 | 0x69);
        block.write(0x0e);                                  // ... = 2000
        block.writeBytes("v".repeat(2000).getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i < 4000; i++) {
            block.write(0x80 | 62);                         // 4000 references to that entry: 8 MB decoded
        }
        byte[] bytes = block.toByteArray();
        assertThatThrownBy(() -> new Hpack.Decoder(4096, 16 * 1024).decode(bytes, 0, bytes.length))
            .isInstanceOf(Hpack.HpackException.class).hasMessageContaining("too large");
    }

    @Test
    void encoderOutputIsReadableByTheDecoder() throws Exception {
        Hpack.Encoder encoder = new Hpack.Encoder();
        encoder.status(200);
        encoder.status(418);
        encoder.field("content-type", "application/json");
        encoder.field("x-custom", "v".repeat(300));
        encoder.field("set-cookie", "a=b; Path=/");
        byte[] block = encoder.toByteArray();

        assertThat(new Hpack.Decoder(4096, 16 * 1024).decode(block, 0, block.length)).containsExactly(
            new Hpack.Field(":status", "200"), new Hpack.Field(":status", "418"),
            new Hpack.Field("content-type", "application/json"),
            new Hpack.Field("x-custom", "v".repeat(300)),
            new Hpack.Field("set-cookie", "a=b; Path=/"));
    }
}
