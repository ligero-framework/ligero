package com.ligero.mcp;

import com.ligero.middleware.Middleware;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A Model Context Protocol (MCP) server: expose your application's capabilities
 * as <b>tools</b> that an LLM (Claude, etc.) can call. Speaks JSON-RPC 2.0 over
 * the <b>Streamable HTTP</b> transport, served as a normal Ligero middleware.
 *
 * <pre>{@code
 * McpServer mcp = McpServer.create("weather", "1.0.0")
 *     .tool("get_forecast", "Get the forecast for a city",
 *           McpServer.objectSchema(Map.of("city", McpServer.stringParam("City name")), "city"),
 *           args -> weather.forecast((String) args.get("city")));
 *
 * app.use(mcp.http("/mcp"));   // POST /mcp speaks MCP
 * }</pre>
 *
 * <p>Implements the protocol handshake ({@code initialize}), {@code ping} and
 * {@code tools/list} + {@code tools/call}. A single JSON-RPC message per POST is
 * supported (the common client behaviour); batching and SSE streaming can be
 * layered on later.</p>
 */
public final class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);
    /** The MCP revision this server implements. */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    private final String name;
    private final String version;
    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    private McpServer(String name, String version) {
        this.name = name;
        this.version = version;
    }

    public static McpServer create(String name, String version) {
        return new McpServer(name, version);
    }

    /** Registers a tool the LLM can call. {@code inputSchema} is JSON Schema. */
    public McpServer tool(String name, String description, Map<String, Object> inputSchema,
                          Function<Map<String, Object>, String> handler) {
        tools.put(name, new McpTool(name, description, inputSchema, handler));
        return this;
    }

    /** A middleware that serves this MCP server at {@code path} (POST). */
    public Middleware http(String path) {
        return new McpHttp(path, this);
    }

    // ---- JSON Schema helpers ------------------------------------------------

    /** An {@code object} schema from named property schemas and required keys. */
    public static Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required.length > 0) {
            schema.put("required", List.of(required));
        }
        return schema;
    }

    public static Map<String, Object> stringParam(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> numberParam(String description) {
        return Map.of("type", "number", "description", description);
    }

    // ---- JSON-RPC dispatch --------------------------------------------------

    /**
     * Handles a single JSON-RPC message and returns the response, or {@code null}
     * for a notification (no {@code id}) that produces no reply.
     */
    public Map<String, Object> handle(Map<String, Object> message) {
        Object id = message.get("id");
        Object methodValue = message.get("method");
        if (!(methodValue instanceof String method)) {
            return error(id, -32600, "Invalid Request");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = message.get("params") instanceof Map<?, ?> p
            ? (Map<String, Object>) p : Map.of();
        try {
            Object result;
            switch (method) {
                case "initialize" -> result = initialize();
                case "ping" -> result = Map.of();
                case "tools/list" -> result = Map.of("tools", toolDescriptors());
                case "tools/call" -> result = callTool(params);
                default -> {
                    if (method.startsWith("notifications/")) {
                        return null; // fire-and-forget
                    }
                    return error(id, -32601, "Method not found: " + method);
                }
            }
            if (id == null) {
                return null; // it was a notification
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", result);
            return response;
        } catch (RuntimeException e) {
            log.error("MCP method {} failed", method, e);
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private Map<String, Object> initialize() {
        return Map.of(
            "protocolVersion", PROTOCOL_VERSION,
            "capabilities", Map.of("tools", Map.of("listChanged", false)),
            "serverInfo", Map.of("name", name, "version", version));
    }

    private List<Map<String, Object>> toolDescriptors() {
        return tools.values().stream().map(tool -> {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("name", tool.name());
            descriptor.put("description", tool.description());
            descriptor.put("inputSchema", tool.inputSchema());
            return descriptor;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(Map<String, Object> params) {
        String toolName = String.valueOf(params.get("name"));
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> a
            ? (Map<String, Object>) a : Map.of();
        McpTool tool = tools.get(toolName);
        if (tool == null) {
            return toolResult("Unknown tool: " + toolName, true);
        }
        try {
            return toolResult(tool.handler().apply(arguments), false);
        } catch (RuntimeException e) {
            return toolResult(e.getMessage() == null ? e.toString() : e.getMessage(), true);
        }
    }

    private static Map<String, Object> toolResult(String text, boolean isError) {
        return Map.of(
            "content", List.of(Map.of("type", "text", "text", text == null ? "" : text)),
            "isError", isError);
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id); // may be null, per JSON-RPC
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }
}
