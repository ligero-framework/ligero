package com.ligero.http;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An error response in the "Problem Details for HTTP APIs" format
 * (<a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457</a>), served as
 * {@code application/problem+json}:
 *
 * <pre>{@code
 * {"type":"https://example.com/probs/out-of-stock","title":"Out of stock",
 *  "status":409,"detail":"Item 42 is no longer available","instance":"/orders",
 *  "item":42}
 * }</pre>
 *
 * <p>Throw one with {@link ProblemException}, send one with
 * {@link Context#problem(ProblemDetail)}, or turn every framework error into
 * one with {@code app.problemDetails(true)}.</p>
 *
 * <pre>{@code
 * throw new ProblemException(ProblemDetail.of(409)
 *     .type("https://example.com/probs/out-of-stock")
 *     .title("Out of stock")
 *     .detail("Item " + id + " is no longer available")
 *     .with("item", id));
 * }</pre>
 *
 * <p>Extension members are rendered as JSON without a JSON library: strings,
 * numbers, booleans, {@code null}, collections, arrays and maps of those;
 * anything else is rendered as its {@code toString()} string.</p>
 */
public final class ProblemDetail {

    /** Media type of a problem details body. */
    public static final String MEDIA_TYPE = "application/problem+json";

    /** The default type: the problem is just the HTTP status. */
    public static final String BLANK_TYPE = "about:blank";

    private static final Set<String> RESERVED = Set.of("type", "title", "status", "detail", "instance");

    private final int status;
    private String type = BLANK_TYPE;
    private String title;
    private String detail;
    private String instance;
    private final Map<String, Object> extensions = new LinkedHashMap<>();

    private ProblemDetail(int status) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Not an HTTP status: " + status);
        }
        this.status = status;
        this.title = reasonPhrase(status);
    }

    /** A problem for {@code status}, titled with the standard reason phrase. */
    public static ProblemDetail of(int status) {
        return new ProblemDetail(status);
    }

    /** A problem for {@code status} with a human-readable explanation. */
    public static ProblemDetail of(int status, String detail) {
        return new ProblemDetail(status).detail(detail);
    }

    /** A URI identifying the problem type; {@code about:blank} by default. */
    public ProblemDetail type(String type) {
        this.type = Objects.requireNonNull(type, "type");
        return this;
    }

    /** Short summary of the problem type; the status reason phrase by default. */
    public ProblemDetail title(String title) {
        this.title = title;
        return this;
    }

    /** Explanation specific to this occurrence. */
    public ProblemDetail detail(String detail) {
        this.detail = detail;
        return this;
    }

    /** A URI reference identifying this occurrence (the request path, by default). */
    public ProblemDetail instance(String instance) {
        this.instance = instance;
        return this;
    }

    /** Adds an extension member, e.g. {@code with("balance", 30)}. */
    public ProblemDetail with(String name, Object value) {
        Objects.requireNonNull(name, "name");
        if (RESERVED.contains(name)) {
            throw new IllegalArgumentException("'" + name + "' is a standard member; use its own setter");
        }
        extensions.put(name, value);
        return this;
    }

    public int status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }

    public String instance() {
        return instance;
    }

    public Map<String, Object> extensions() {
        return Collections.unmodifiableMap(extensions);
    }

    /** The JSON body. */
    public String toJson() {
        StringBuilder out = new StringBuilder(128);
        out.append("{\"type\":");
        string(out, type);
        if (title != null) {
            out.append(",\"title\":");
            string(out, title);
        }
        out.append(",\"status\":").append(status);
        if (detail != null) {
            out.append(",\"detail\":");
            string(out, detail);
        }
        if (instance != null) {
            out.append(",\"instance\":");
            string(out, instance);
        }
        for (Map.Entry<String, Object> member : extensions.entrySet()) {
            out.append(',');
            string(out, member.getKey());
            out.append(':');
            value(out, member.getValue(), 0);
        }
        return out.append('}').toString();
    }

    @Override
    public String toString() {
        return toJson();
    }

    private static void value(StringBuilder out, Object value, int depth) {
        if (depth > 32) {
            throw new IllegalArgumentException("Problem detail extension nested too deeply");
        }
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean
            || value instanceof Integer || value instanceof Long || value instanceof Short
            || value instanceof Byte || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                out.append("null");
            } else {
                out.append(value);
            }
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(out, String.valueOf(entry.getKey()));
                out.append(':');
                value(out, entry.getValue(), depth + 1);
            }
            out.append('}');
        } else if (value instanceof Collection<?> collection) {
            array(out, collection, depth);
        } else if (value instanceof Object[] array) {
            array(out, Arrays.asList(array), depth);
        } else {
            string(out, value.toString());
        }
    }

    private static void array(StringBuilder out, Collection<?> values, int depth) {
        out.append('[');
        boolean first = true;
        for (Object element : values) {
            if (!first) {
                out.append(',');
            }
            first = false;
            value(out, element, depth + 1);
        }
        out.append(']');
    }

    static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c"); // never closes a <script> it is embedded in
                case ' ' -> out.append("\\u2028");
                case ' ' -> out.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** The standard reason phrase for {@code status}, or {@code "Error"}. */
    public static String reasonPhrase(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 402 -> "Payment Required";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 411 -> "Length Required";
            case 412 -> "Precondition Failed";
            case 413 -> "Content Too Large";
            case 414 -> "URI Too Long";
            case 415 -> "Unsupported Media Type";
            case 416 -> "Range Not Satisfiable";
            case 417 -> "Expectation Failed";
            case 421 -> "Misdirected Request";
            case 422 -> "Unprocessable Content";
            case 423 -> "Locked";
            case 424 -> "Failed Dependency";
            case 425 -> "Too Early";
            case 426 -> "Upgrade Required";
            case 428 -> "Precondition Required";
            case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 451 -> "Unavailable For Legal Reasons";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            case 505 -> "HTTP Version Not Supported";
            default -> status < 400 ? "OK" : "Error";
        };
    }
}
