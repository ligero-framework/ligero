package com.ligero.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed {@code multipart/form-data} body: text fields plus uploaded files.
 * Obtained through {@code ctx.multipart()}.
 */
public final class Multipart {

    /** One uploaded file part. */
    public record UploadedFile(String fieldName, String filename, String contentType, byte[] content) {
    }

    private final Map<String, List<String>> fields = new LinkedHashMap<>();
    private final List<UploadedFile> files = new ArrayList<>();

    public Map<String, List<String>> fields() {
        return fields;
    }

    public String field(String name) {
        List<String> values = fields.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public List<UploadedFile> files() {
        return files;
    }

    public UploadedFile file(String fieldName) {
        return files.stream().filter(f -> f.fieldName().equals(fieldName)).findFirst().orElse(null);
    }

    /**
     * Parses a multipart body. The boundary comes from the request's
     * {@code Content-Type} header.
     *
     * @throws BadRequestException on malformed multipart content
     */
    public static Multipart parse(byte[] body, String contentTypeHeader) {
        String boundary = extractBoundary(contentTypeHeader);
        if (boundary == null) {
            throw new BadRequestException("Missing multipart boundary");
        }
        Multipart result = new Multipart();
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.UTF_8);

        int pos = indexOf(body, delimiter, 0);
        if (pos < 0) {
            throw new BadRequestException("Malformed multipart body");
        }
        pos += delimiter.length;
        while (true) {
            // "--" after the delimiter marks the closing boundary
            if (pos + 1 < body.length && body[pos] == '-' && body[pos + 1] == '-') {
                break;
            }
            pos = skipCrLf(body, pos);
            int headersEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), pos);
            if (headersEnd < 0) {
                throw new BadRequestException("Malformed multipart part headers");
            }
            String headerBlock = new String(body, pos, headersEnd - pos, StandardCharsets.UTF_8);
            int contentStart = headersEnd + 4;
            int next = indexOf(body, delimiter, contentStart);
            if (next < 0) {
                throw new BadRequestException("Unterminated multipart part");
            }
            int contentEnd = next - 2; // strip the CRLF that precedes the boundary
            if (contentEnd < contentStart) {
                contentEnd = contentStart;
            }
            byte[] content = new byte[contentEnd - contentStart];
            System.arraycopy(body, contentStart, content, 0, content.length);

            String name = headerAttribute(headerBlock, "name");
            String filename = headerAttribute(headerBlock, "filename");
            if (filename != null) {
                result.files.add(new UploadedFile(name, filename,
                    headerValue(headerBlock, "Content-Type"), content));
            } else if (name != null) {
                result.fields.computeIfAbsent(name, n -> new ArrayList<>())
                    .add(new String(content, StandardCharsets.UTF_8));
            }
            pos = next + delimiter.length;
        }
        return result;
    }

    static String extractBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "boundary=", 0, 9)) {
                String boundary = trimmed.substring(9).trim();
                if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() > 1) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary.isEmpty() ? null : boundary;
            }
        }
        return null;
    }

    private static String headerAttribute(String headers, String attribute) {
        for (String line : headers.split("\r\n")) {
            if (!line.regionMatches(true, 0, "Content-Disposition:", 0, 20)) {
                continue;
            }
            for (String piece : line.split(";")) {
                String trimmed = piece.trim();
                if (trimmed.regionMatches(true, 0, attribute + "=", 0, attribute.length() + 1)) {
                    String value = trimmed.substring(attribute.length() + 1).trim();
                    if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
                        value = value.substring(1, value.length() - 1);
                    }
                    return value;
                }
            }
        }
        return null;
    }

    private static String headerValue(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                return line.substring(name.length() + 1).trim();
            }
        }
        return null;
    }

    private static int skipCrLf(byte[] body, int pos) {
        if (pos + 1 < body.length && body[pos] == '\r' && body[pos + 1] == '\n') {
            return pos + 2;
        }
        return pos;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
