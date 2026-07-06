package com.ligero.devtools;

import com.ligero.beans.BeanGraph;

import java.util.List;
import java.util.Map;

/**
 * Minimal hand-rolled JSON writer for the two devtools payloads (graph and
 * traces). Keeps ligero-devtools free of any JSON library so it only depends
 * on ligero-core.
 */
final class Json {

    private Json() {
    }

    static String graph(BeanGraph graph, Map<String, String> stereotypeOverlay,
                        List<String> unspied) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"nodes\":[");
        boolean first = true;
        for (BeanGraph.Node node : graph.nodes()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            // The graph tags nodes by binding type; when the annotation lives on
            // the implementation class, the recorder saw it at decoration time.
            String stereotype = node.stereotype();
            if ("bean".equals(stereotype)) {
                stereotype = stereotypeOverlay.getOrDefault(node.type(), stereotype);
            }
            sb.append("{\"type\":").append(str(node.type()))
              .append(",\"stereotype\":").append(str(stereotype)).append('}');
        }
        sb.append("],\"edges\":[");
        first = true;
        for (BeanGraph.Edge edge : graph.edges()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"from\":").append(str(edge.from()))
              .append(",\"to\":").append(str(edge.to())).append('}');
        }
        sb.append("],\"unspied\":[");
        first = true;
        for (String type : unspied) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(str(type));
        }
        return sb.append("]}").toString();
    }

    static String traces(List<RequestTrace> traces) {
        StringBuilder sb = new StringBuilder(2048).append('[');
        for (int i = 0; i < traces.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            trace(sb, traces.get(i));
        }
        return sb.append(']').toString();
    }

    static String trace(RequestTrace trace) {
        StringBuilder sb = new StringBuilder(512);
        trace(sb, trace);
        return sb.toString();
    }

    private static void trace(StringBuilder sb, RequestTrace t) {
        sb.append("{\"id\":").append(str(t.id()))
          .append(",\"method\":").append(str(t.method()))
          .append(",\"path\":").append(str(t.path()))
          .append(",\"status\":").append(t.status())
          .append(",\"startedAt\":").append(t.startedAtMs())
          .append(",\"durationUs\":").append(t.durationUs())
          .append(",\"calls\":[");
        List<RequestTrace.Call> calls = t.calls();
        for (int i = 0; i < calls.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            RequestTrace.Call c = calls.get(i);
            sb.append("{\"order\":").append(c.order())
              .append(",\"depth\":").append(c.depth())
              .append(",\"bean\":").append(str(c.bean()))
              .append(",\"declaredBy\":").append(str(c.declaredBy()))
              .append(",\"stereotype\":").append(str(c.stereotype()))
              .append(",\"method\":").append(str(c.method()))
              .append(",\"args\":").append(str(c.args()))
              .append(",\"result\":").append(str(c.result()))
              .append(",\"error\":").append(str(c.error()))
              .append(",\"durationUs\":").append(c.durationUs())
              .append('}');
        }
        sb.append("]}");
    }

    static String str(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
