package com.ligero.devtools;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A tiny, dependency-free serializer that turns an arbitrary object — a layer's
 * argument or return value — into real JSON for the devtools dashboard.
 *
 * <p>It is deliberately conservative so it never becomes a burden at runtime:
 * it caps recursion {@link #MAX_DEPTH depth}, the number of {@link #MAX_ELEMENTS
 * elements} per collection/map and the {@link #MAX_STRING string} length, guards
 * against reference cycles, and falls back to {@code toString()} for anything it
 * cannot introspect (or any type living under {@code java.*}). It reflects only
 * over public record components and JavaBean getters, so it exposes the same
 * shape a JSON body mapper would — without pulling one in.</p>
 */
final class JsonValue {

    private static final int MAX_DEPTH = 6;
    private static final int MAX_ELEMENTS = 200;
    private static final int MAX_STRING = 2000;

    private JsonValue() {
    }

    /** Serializes a method's arguments as a JSON array (empty array if none). */
    static String array(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(64).append('[');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            write(sb, args[i], 0, new IdentityHashMap<>());
        }
        return sb.append(']').toString();
    }

    /** Serializes a single value as a JSON value. */
    static String of(Object value) {
        StringBuilder sb = new StringBuilder(64);
        write(sb, value, 0, new IdentityHashMap<>());
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value, int depth,
                              IdentityHashMap<Object, Boolean> seen) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
            return;
        }
        if (value instanceof Number n) {
            writeNumber(sb, n);
            return;
        }
        if (value instanceof CharSequence || value instanceof Character) {
            sb.append(Json.str(clip(value.toString())));
            return;
        }
        if (value instanceof Enum<?> e) {
            sb.append(Json.str(e.name()));
            return;
        }
        if (value instanceof Optional<?> opt) {
            write(sb, opt.orElse(null), depth, seen);
            return;
        }
        if (value instanceof byte[] bytes) {
            sb.append(Json.str(bytes.length + " bytes"));
            return;
        }
        // Guard depth and cycles before descending into containers/objects.
        if (depth >= MAX_DEPTH) {
            sb.append(Json.str(clip(value.toString())));
            return;
        }
        if (seen.put(value, Boolean.TRUE) != null) {
            sb.append(Json.str("<recursive>"));
            return;
        }
        try {
            if (value.getClass().isArray()) {
                writeArray(sb, value, depth, seen);
            } else if (value instanceof Map<?, ?> map) {
                writeMap(sb, map, depth, seen);
            } else if (value instanceof Collection<?> col) {
                writeIterable(sb, col, depth, seen);
            } else if (isOpaque(value.getClass())) {
                sb.append(Json.str(clip(value.toString())));
            } else if (value.getClass().isRecord()) {
                writeRecord(sb, value, depth, seen);
            } else {
                writeBean(sb, value, depth, seen);
            }
        } finally {
            seen.remove(value);
        }
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            sb.append(Json.str(n.toString()));  // JSON has no NaN/Infinity literal
        } else {
            sb.append(n);
        }
    }

    private static void writeArray(StringBuilder sb, Object array, int depth,
                                   IdentityHashMap<Object, Boolean> seen) {
        int len = Array.getLength(array);
        sb.append('[');
        for (int i = 0; i < len && i < MAX_ELEMENTS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            write(sb, Array.get(array, i), depth + 1, seen);
        }
        appendTruncation(sb, len);
        sb.append(']');
    }

    private static void writeIterable(StringBuilder sb, Iterable<?> it, int depth,
                                      IdentityHashMap<Object, Boolean> seen) {
        sb.append('[');
        int i = 0;
        for (Object item : it) {
            if (i >= MAX_ELEMENTS) {
                sb.append(",\"…more…\"");
                break;
            }
            if (i > 0) {
                sb.append(',');
            }
            write(sb, item, depth + 1, seen);
            i++;
        }
        sb.append(']');
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> map, int depth,
                                 IdentityHashMap<Object, Boolean> seen) {
        sb.append('{');
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (i >= MAX_ELEMENTS) {
                sb.append(",\"…more…\":true");
                break;
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Json.str(String.valueOf(entry.getKey()))).append(':');
            write(sb, entry.getValue(), depth + 1, seen);
            i++;
        }
        sb.append('}');
    }

    private static void writeRecord(StringBuilder sb, Object value, int depth,
                                    IdentityHashMap<Object, Boolean> seen) {
        RecordComponent[] components = value.getClass().getRecordComponents();
        sb.append('{');
        boolean first = true;
        for (RecordComponent component : components) {
            Object member = readMember(component.getAccessor(), value);
            if (member == SKIP) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(Json.str(component.getName())).append(':');
            write(sb, member, depth + 1, seen);
        }
        sb.append('}');
    }

    private static void writeBean(StringBuilder sb, Object value, int depth,
                                  IdentityHashMap<Object, Boolean> seen) {
        // Ordered by property name for a stable, readable rendering.
        Map<String, Object> props = new TreeMap<>();
        for (Method method : value.getClass().getMethods()) {
            String name = propertyName(method);
            if (name == null) {
                continue;
            }
            Object member = readMember(method, value);
            if (member != SKIP) {
                props.put(name, member);
            }
        }
        if (props.isEmpty()) {
            sb.append(Json.str(clip(value.toString())));  // no getters — best effort
            return;
        }
        writeMap(sb, props, depth, seen);
    }

    /** Bean-getter property name ({@code getX}/{@code isX}) or {@code null}. */
    private static String propertyName(Method m) {
        if (m.getParameterCount() != 0 || m.getReturnType() == void.class
            || Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class) {
            return null;
        }
        String n = m.getName();
        if (n.startsWith("get") && n.length() > 3) {
            return decapitalize(n.substring(3));
        }
        if (n.startsWith("is") && n.length() > 2
            && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
            return decapitalize(n.substring(2));
        }
        return null;
    }

    private static final Object SKIP = new Object();

    private static Object readMember(Method accessor, Object owner) {
        try {
            accessor.setAccessible(true);
            return accessor.invoke(owner);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return SKIP;  // an accessor that throws must not break the trace
        }
    }

    /** Types we do not introspect: JDK/platform classes are shown via toString. */
    private static boolean isOpaque(Class<?> type) {
        Package p = type.getPackage();
        if (p == null) {
            return false;
        }
        String name = p.getName();
        return name.startsWith("java.") || name.startsWith("javax.")
            || name.startsWith("jakarta.") || name.startsWith("sun.")
            || name.startsWith("com.sun.") || name.startsWith("jdk.");
    }

    private static void appendTruncation(StringBuilder sb, int total) {
        if (total > MAX_ELEMENTS) {
            sb.append(",\"…more…\"");
        }
    }

    private static String clip(String s) {
        return s.length() <= MAX_STRING ? s : s.substring(0, MAX_STRING) + "…";
    }

    private static String decapitalize(String s) {
        if (s.length() > 1 && Character.isUpperCase(s.charAt(1))) {
            return s;  // e.g. "URL" stays "URL"
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
