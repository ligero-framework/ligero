package com.ligero.auth;

import java.util.Arrays;

/**
 * Converts ECDSA signatures between the JVM's ASN.1/DER encoding and the fixed
 * R||S concatenation JOSE uses for ES256. Package-private helper for {@link Jwt}.
 */
final class Ecdsa {

    private Ecdsa() {
    }

    /** DER {@code SEQUENCE { INTEGER r, INTEGER s }} -> fixed-length {@code R||S}. */
    static byte[] derToJose(byte[] der, int outLen) {
        int half = outLen / 2;
        // SEQUENCE
        int offset = 2;
        if ((der[1] & 0x80) != 0) {
            offset += der[1] & 0x7F; // long-form length
        }
        // INTEGER r
        offset++; // 0x02 tag
        int rLen = der[offset++];
        int rStart = offset;
        offset += rLen;
        // INTEGER s
        offset++; // 0x02 tag
        int sLen = der[offset++];
        int sStart = offset;

        byte[] out = new byte[outLen];
        copyInto(der, rStart, rLen, out, 0, half);
        copyInto(der, sStart, sLen, out, half, half);
        return out;
    }

    private static void copyInto(byte[] src, int srcStart, int srcLen,
                                 byte[] dst, int dstStart, int slot) {
        // strip a leading sign byte, right-align into the slot
        int start = srcStart;
        int len = srcLen;
        while (len > slot && src[start] == 0) {
            start++;
            len--;
        }
        System.arraycopy(src, start, dst, dstStart + slot - len, len);
    }

    /** Fixed-length {@code R||S} -> DER {@code SEQUENCE { INTEGER r, INTEGER s }}. */
    static byte[] joseToDer(byte[] jose) {
        int half = jose.length / 2;
        byte[] r = trimAndPad(Arrays.copyOfRange(jose, 0, half));
        byte[] s = trimAndPad(Arrays.copyOfRange(jose, half, jose.length));
        int seqLen = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + seqLen];
        int i = 0;
        der[i++] = 0x30; // SEQUENCE
        der[i++] = (byte) seqLen;
        der[i++] = 0x02; // INTEGER
        der[i++] = (byte) r.length;
        System.arraycopy(r, 0, der, i, r.length);
        i += r.length;
        der[i++] = 0x02; // INTEGER
        der[i++] = (byte) s.length;
        System.arraycopy(s, 0, der, i, s.length);
        return der;
    }

    /** Strip leading zeros, then prepend a 0x00 if the high bit is set (keep positive). */
    private static byte[] trimAndPad(byte[] value) {
        int start = 0;
        while (start < value.length - 1 && value[start] == 0) {
            start++;
        }
        byte[] trimmed = Arrays.copyOfRange(value, start, value.length);
        if ((trimmed[0] & 0x80) != 0) {
            byte[] padded = new byte[trimmed.length + 1];
            System.arraycopy(trimmed, 0, padded, 1, trimmed.length);
            return padded;
        }
        return trimmed;
    }
}
