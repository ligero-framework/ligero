package com.ligero.server.pluma;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * HPACK (RFC 7541): the header compression HTTP/2 uses.
 *
 * <p>Decoding is where the risk is — the peer controls a stateful table and
 * variable-length encodings — so every length, index and size is bounded:
 * integers that would overflow, table-size updates beyond what we allow,
 * invalid Huffman padding and header lists larger than the configured limit
 * are all errors rather than something to cope with.</p>
 *
 * <p>Encoding is deliberately simple: fields are sent as literals (with an
 * indexed name when the static table has one) and never added to the dynamic
 * table. That costs a few bytes per response and removes a whole class of
 * state to get wrong.</p>
 */
final class Hpack {

    /** A problem in a header block: always fatal for the connection, since the tables may be out of sync. */
    static final class HpackException extends Exception {
        HpackException(String message) {
            super(message);
        }
    }

    private static final String[][] STATIC_TABLE = {
        {":authority", ""}, {":method", "GET"}, {":method", "POST"}, {":path", "/"}, {":path", "/index.html"},
        {":scheme", "http"}, {":scheme", "https"}, {":status", "200"}, {":status", "204"}, {":status", "206"},
        {":status", "304"}, {":status", "400"}, {":status", "404"}, {":status", "500"}, {"accept-charset", ""},
        {"accept-encoding", "gzip, deflate"}, {"accept-language", ""}, {"accept-ranges", ""}, {"accept", ""},
        {"access-control-allow-origin", ""}, {"age", ""}, {"allow", ""}, {"authorization", ""},
        {"cache-control", ""}, {"content-disposition", ""}, {"content-encoding", ""}, {"content-language", ""},
        {"content-length", ""}, {"content-location", ""}, {"content-range", ""}, {"content-type", ""},
        {"cookie", ""}, {"date", ""}, {"etag", ""}, {"expect", ""}, {"expires", ""}, {"from", ""}, {"host", ""},
        {"if-match", ""}, {"if-modified-since", ""}, {"if-none-match", ""}, {"if-range", ""},
        {"if-unmodified-since", ""}, {"last-modified", ""}, {"link", ""}, {"location", ""}, {"max-forwards", ""},
        {"proxy-authenticate", ""}, {"proxy-authorization", ""}, {"range", ""}, {"referer", ""}, {"refresh", ""},
        {"retry-after", ""}, {"server", ""}, {"set-cookie", ""}, {"strict-transport-security", ""},
        {"transfer-encoding", ""}, {"user-agent", ""}, {"vary", ""}, {"via", ""}, {"www-authenticate", ""},
    };

    /** Huffman code of each byte value (RFC 7541, Appendix B), right-aligned. */
    private static final int[] HUFFMAN_CODES = {
                0x1ff8, 0x7fffd8, 0xfffffe2, 0xfffffe3, 0xfffffe4, 0xfffffe5, 0xfffffe6, 0xfffffe7,
        0xfffffe8, 0xffffea, 0x3ffffffc, 0xfffffe9, 0xfffffea, 0x3ffffffd, 0xfffffeb, 0xfffffec,
        0xfffffed, 0xfffffee, 0xfffffef, 0xffffff0, 0xffffff1, 0xffffff2, 0x3ffffffe, 0xffffff3,
        0xffffff4, 0xffffff5, 0xffffff6, 0xffffff7, 0xffffff8, 0xffffff9, 0xffffffa, 0xffffffb,
        0x14, 0x3f8, 0x3f9, 0xffa, 0x1ff9, 0x15, 0xf8, 0x7fa,
        0x3fa, 0x3fb, 0xf9, 0x7fb, 0xfa, 0x16, 0x17, 0x18,
        0x0, 0x1, 0x2, 0x19, 0x1a, 0x1b, 0x1c, 0x1d,
        0x1e, 0x1f, 0x5c, 0xfb, 0x7ffc, 0x20, 0xffb, 0x3fc,
        0x1ffa, 0x21, 0x5d, 0x5e, 0x5f, 0x60, 0x61, 0x62,
        0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a,
        0x6b, 0x6c, 0x6d, 0x6e, 0x6f, 0x70, 0x71, 0x72,
        0xfc, 0x73, 0xfd, 0x1ffb, 0x7fff0, 0x1ffc, 0x3ffc, 0x22,
        0x7ffd, 0x3, 0x23, 0x4, 0x24, 0x5, 0x25, 0x26,
        0x27, 0x6, 0x74, 0x75, 0x28, 0x29, 0x2a, 0x7,
        0x2b, 0x76, 0x2c, 0x8, 0x9, 0x2d, 0x77, 0x78,
        0x79, 0x7a, 0x7b, 0x7ffe, 0x7fc, 0x3ffd, 0x1ffd, 0xffffffc,
        0xfffe6, 0x3fffd2, 0xfffe7, 0xfffe8, 0x3fffd3, 0x3fffd4, 0x3fffd5, 0x7fffd9,
        0x3fffd6, 0x7fffda, 0x7fffdb, 0x7fffdc, 0x7fffdd, 0x7fffde, 0xffffeb, 0x7fffdf,
        0xffffec, 0xffffed, 0x3fffd7, 0x7fffe0, 0xffffee, 0x7fffe1, 0x7fffe2, 0x7fffe3,
        0x7fffe4, 0x1fffdc, 0x3fffd8, 0x7fffe5, 0x3fffd9, 0x7fffe6, 0x7fffe7, 0xffffef,
        0x3fffda, 0x1fffdd, 0xfffe9, 0x3fffdb, 0x3fffdc, 0x7fffe8, 0x7fffe9, 0x1fffde,
        0x7fffea, 0x3fffdd, 0x3fffde, 0xfffff0, 0x1fffdf, 0x3fffdf, 0x7fffeb, 0x7fffec,
        0x1fffe0, 0x1fffe1, 0x3fffe0, 0x1fffe2, 0x7fffed, 0x3fffe1, 0x7fffee, 0x7fffef,
        0xfffea, 0x3fffe2, 0x3fffe3, 0x3fffe4, 0x7ffff0, 0x3fffe5, 0x3fffe6, 0x7ffff1,
        0x3ffffe0, 0x3ffffe1, 0xfffeb, 0x7fff1, 0x3fffe7, 0x7ffff2, 0x3fffe8, 0x1ffffec,
        0x3ffffe2, 0x3ffffe3, 0x3ffffe4, 0x7ffffde, 0x7ffffdf, 0x3ffffe5, 0xfffff1, 0x1ffffed,
        0x7fff2, 0x1fffe3, 0x3ffffe6, 0x7ffffe0, 0x7ffffe1, 0x3ffffe7, 0x7ffffe2, 0xfffff2,
        0x1fffe4, 0x1fffe5, 0x3ffffe8, 0x3ffffe9, 0xffffffd, 0x7ffffe3, 0x7ffffe4, 0x7ffffe5,
        0xfffec, 0xfffff3, 0xfffed, 0x1fffe6, 0x3fffe9, 0x1fffe7, 0x1fffe8, 0x7ffff3,
        0x3fffea, 0x3fffeb, 0x1ffffee, 0x1ffffef, 0xfffff4, 0xfffff5, 0x3ffffea, 0x7ffff4,
        0x3ffffeb, 0x7ffffe6, 0x3ffffec, 0x3ffffed, 0x7ffffe7, 0x7ffffe8, 0x7ffffe9, 0x7ffffea,
        0x7ffffeb, 0xffffffe, 0x7ffffec, 0x7ffffed, 0x7ffffee, 0x7ffffef, 0x7fffff0, 0x3ffffee
    };

    /** Length in bits of each Huffman code. */
    private static final byte[] HUFFMAN_LENGTHS = {
                13, 23, 28, 28, 28, 28, 28, 28, 28, 24, 30, 28, 28, 30, 28, 28,
        28, 28, 28, 28, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 28,
        6, 10, 10, 12, 13, 6, 8, 11, 10, 10, 8, 11, 8, 6, 6, 6,
        5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 7, 8, 15, 6, 12, 10,
        13, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 8, 7, 8, 13, 19, 13, 14, 6,
        15, 5, 6, 5, 6, 5, 6, 6, 6, 5, 7, 7, 6, 6, 6, 5,
        6, 7, 6, 5, 5, 6, 7, 7, 7, 7, 7, 15, 11, 14, 13, 28,
        20, 22, 20, 20, 22, 22, 22, 23, 22, 23, 23, 23, 23, 23, 24, 23,
        24, 24, 22, 23, 24, 23, 23, 23, 23, 21, 22, 23, 22, 23, 23, 24,
        22, 21, 20, 22, 22, 23, 23, 21, 23, 22, 22, 24, 21, 22, 23, 23,
        21, 21, 22, 21, 23, 22, 23, 23, 20, 22, 22, 22, 23, 22, 22, 23,
        26, 26, 20, 19, 22, 23, 22, 25, 26, 26, 26, 27, 27, 26, 24, 25,
        19, 21, 26, 27, 27, 26, 27, 24, 21, 21, 26, 26, 28, 27, 27, 27,
        20, 24, 20, 21, 22, 21, 21, 23, 22, 22, 25, 25, 24, 24, 26, 23,
        26, 27, 26, 26, 27, 27, 27, 27, 27, 28, 27, 27, 27, 27, 27, 26
    };

    /** Decoding trie: {@code TRIE[node][bit]} is the next node, or {@code -(symbol + 1)} at a leaf. */
    private static final int[][] TRIE = buildTrie();

    private static int[][] buildTrie() {
        List<int[]> nodes = new ArrayList<>();
        nodes.add(new int[2]);
        for (int symbol = 0; symbol < 256; symbol++) {
            int node = 0;
            for (int bit = HUFFMAN_LENGTHS[symbol] - 1; bit >= 0; bit--) {
                int b = (HUFFMAN_CODES[symbol] >>> bit) & 1;
                if (bit == 0) {
                    nodes.get(node)[b] = -(symbol + 1);
                } else {
                    int next = nodes.get(node)[b];
                    if (next == 0) {
                        next = nodes.size();
                        nodes.add(new int[2]);
                        nodes.get(node)[b] = next;
                    }
                    node = next;
                }
            }
        }
        return nodes.toArray(new int[0][]);
    }

    private Hpack() {
    }

    /** One header field. */
    record Field(String name, String value) {
    }

    // ------------------------------------------------------------------ decoding

    /** Stateful decoder for one connection's request header blocks. */
    static final class Decoder {

        private final int maxTableSize;
        private final int maxHeaderListSize;
        private final ArrayDeque<Field> dynamic = new ArrayDeque<>();
        private int tableSize;
        private int tableLimit;

        private byte[] data;
        private int pos;
        private int end;

        /**
         * @param maxTableSize      the dynamic table size we advertised (SETTINGS_HEADER_TABLE_SIZE)
         * @param maxHeaderListSize upper bound on the decoded size of one header list
         */
        Decoder(int maxTableSize, int maxHeaderListSize) {
            this.maxTableSize = maxTableSize;
            this.tableLimit = maxTableSize;
            this.maxHeaderListSize = maxHeaderListSize;
        }

        /** Decodes a complete header block. */
        List<Field> decode(byte[] block, int offset, int length) throws HpackException {
            data = block;
            pos = offset;
            end = offset + length;
            List<Field> fields = new ArrayList<>();
            int listSize = 0;
            boolean fieldSeen = false;
            while (pos < end) {
                int b = data[pos] & 0xff;
                Field field;
                if ((b & 0x80) != 0) {                       // indexed field
                    field = lookup(integer(7));
                } else if ((b & 0xc0) == 0x40) {             // literal, added to the dynamic table
                    int index = integer(6);
                    String name = index == 0 ? string() : lookup(index).name();
                    field = new Field(name, string());
                    add(field);
                } else if ((b & 0xe0) == 0x20) {             // dynamic table size update
                    if (fieldSeen) {
                        throw new HpackException("table size update after a header field");
                    }
                    int size = integer(5);
                    if (size > maxTableSize) {
                        throw new HpackException("table size update beyond the advertised maximum");
                    }
                    tableLimit = size;
                    evict();
                    continue;
                } else {                                      // literal, not indexed (0000) or never indexed (0001)
                    int index = integer(4);
                    String name = index == 0 ? string() : lookup(index).name();
                    field = new Field(name, string());
                }
                fieldSeen = true;
                listSize += field.name().length() + field.value().length() + 32;
                if (listSize > maxHeaderListSize) {
                    throw new HpackException("header list too large");
                }
                fields.add(field);
            }
            return fields;
        }

        private Field lookup(int index) throws HpackException {
            if (index >= 1 && index <= STATIC_TABLE.length) {
                String[] entry = STATIC_TABLE[index - 1];
                return new Field(entry[0], entry[1]);
            }
            int dynamicIndex = index - STATIC_TABLE.length - 1;
            if (index == 0 || dynamicIndex >= dynamic.size()) {
                throw new HpackException("invalid header table index");
            }
            int i = 0;
            for (Field field : dynamic) {       // newest first
                if (i++ == dynamicIndex) {
                    return field;
                }
            }
            throw new HpackException("invalid header table index");
        }

        private void add(Field field) {
            int size = field.name().length() + field.value().length() + 32;
            if (size > tableLimit) {
                dynamic.clear();                // an entry larger than the table empties it
                tableSize = 0;
                return;
            }
            dynamic.addFirst(field);
            tableSize += size;
            evict();
        }

        private void evict() {
            while (tableSize > tableLimit && !dynamic.isEmpty()) {
                Field oldest = dynamic.removeLast();
                tableSize -= oldest.name().length() + oldest.value().length() + 32;
            }
        }

        /** Prefix-coded integer (RFC 7541 §5.1), refused beyond 2^24 or five continuation bytes. */
        private int integer(int prefixBits) throws HpackException {
            int mask = (1 << prefixBits) - 1;
            int value = data[pos++] & mask;
            if (value < mask) {
                return value;
            }
            int shift = 0;
            while (true) {
                if (pos >= end || shift > 21) {
                    throw new HpackException("malformed integer");
                }
                int b = data[pos++] & 0xff;
                value += (b & 0x7f) << shift;
                if (value > (1 << 24)) {
                    throw new HpackException("integer too large");
                }
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
            }
        }

        private String string() throws HpackException {
            if (pos >= end) {
                throw new HpackException("truncated header block");
            }
            boolean huffman = (data[pos] & 0x80) != 0;
            int length = integer(7);
            if (length > end - pos) {
                throw new HpackException("truncated header block");
            }
            if (length > maxHeaderListSize) {
                throw new HpackException("header list too large");
            }
            String text = huffman
                ? huffmanDecode(data, pos, length, maxHeaderListSize)
                : new String(data, pos, length, StandardCharsets.ISO_8859_1);
            pos += length;
            return text;
        }
    }

    /** Decodes a Huffman-coded string, validating its padding (RFC 7541 §5.2). */
    static String huffmanDecode(byte[] data, int offset, int length, int maxDecoded) throws HpackException {
        StringBuilder out = new StringBuilder(length + (length >> 1));
        int node = 0;
        int bitsSinceSymbol = 0;
        boolean allOnes = true;
        for (int i = offset; i < offset + length; i++) {
            int b = data[i];
            for (int bit = 7; bit >= 0; bit--) {
                int value = (b >>> bit) & 1;
                int next = TRIE[node][value];
                bitsSinceSymbol++;
                allOnes &= value == 1;
                if (next < 0) {
                    out.append((char) (-next - 1));
                    if (out.length() > maxDecoded) {
                        throw new HpackException("header list too large");
                    }
                    node = 0;
                    bitsSinceSymbol = 0;
                    allOnes = true;
                } else if (next == 0) {
                    throw new HpackException("invalid Huffman code"); // only the EOS path leads nowhere
                } else {
                    node = next;
                }
            }
        }
        // what is left over must be a prefix of EOS: at most 7 bits, all ones
        if (bitsSinceSymbol > 7 || !allOnes) {
            throw new HpackException("invalid Huffman padding");
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ encoding

    /** Stateless encoder for response header blocks. */
    static final class Encoder {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        void reset() {
            out.reset();
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }

        /** {@code :status} — a single indexed byte for the common codes. */
        void status(int status) {
            int index = switch (status) {
                case 200 -> 8;
                case 204 -> 9;
                case 206 -> 10;
                case 304 -> 11;
                case 400 -> 12;
                case 404 -> 13;
                case 500 -> 14;
                default -> 0;
            };
            if (index != 0) {
                out.write(0x80 | index);
            } else {
                integer(0x00, 4, 8);            // literal without indexing, name = static entry 8 (:status)
                string(Integer.toString(status));
            }
        }

        /** A literal field that is not added to any table. The name must already be lower-case. */
        void field(String name, String value) {
            int index = staticNameIndex(name);
            if (index != 0) {
                integer(0x00, 4, index);
            } else {
                out.write(0x00);
                string(name);
            }
            string(value);
        }

        private void integer(int flags, int prefixBits, int value) {
            int mask = (1 << prefixBits) - 1;
            if (value < mask) {
                out.write(flags | value);
                return;
            }
            out.write(flags | mask);
            int rest = value - mask;
            while (rest >= 0x80) {
                out.write((rest & 0x7f) | 0x80);
                rest >>>= 7;
            }
            out.write(rest);
        }

        /** Raw (non-Huffman) string: ISO-8859-1, anything beyond it replaced. */
        private void string(String text) {
            integer(0x00, 7, text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                out.write(c < 0x100 ? c : '?');
            }
        }

        private static int staticNameIndex(String name) {
            // entries 15.. have unique names and empty values; the pseudo-headers before them are handled apart
            for (int i = 14; i < STATIC_TABLE.length; i++) {
                if (STATIC_TABLE[i][0].equals(name)) {
                    return i + 1;
                }
            }
            return 0;
        }
    }

    /** Huffman-encodes a string — used by tests to produce what real clients send. */
    static byte[] huffmanEncode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long bits = 0;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            int symbol = text.charAt(i) & 0xff;
            bits = (bits << HUFFMAN_LENGTHS[symbol]) | HUFFMAN_CODES[symbol];
            count += HUFFMAN_LENGTHS[symbol];
            while (count >= 8) {
                out.write((int) (bits >>> (count - 8)));
                count -= 8;
            }
        }
        if (count > 0) {
            out.write((int) ((bits << (8 - count)) | (0xff >>> count)));
        }
        return out.toByteArray();
    }
}
