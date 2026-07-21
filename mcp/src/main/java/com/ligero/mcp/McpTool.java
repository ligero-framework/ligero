package com.ligero.mcp;

import java.util.Map;
import java.util.function.Function;

/**
 * A tool an MCP client can call: a name, a human/LLM-readable description, a
 * JSON Schema for its arguments, and a handler that turns the parsed arguments
 * into a text result.
 */
public record McpTool(String name, String description, Map<String, Object> inputSchema,
                      Function<Map<String, Object>, String> handler) {
}
