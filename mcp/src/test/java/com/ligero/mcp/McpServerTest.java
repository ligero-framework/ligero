package com.ligero.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerTest {

    private final McpServer server = McpServer.create("calc", "1.0.0")
        .tool("add", "Add two numbers",
            McpServer.objectSchema(Map.of(
                "a", McpServer.numberParam("first"),
                "b", McpServer.numberParam("second")), "a", "b"),
            args -> String.valueOf(
                ((Number) args.get("a")).doubleValue() + ((Number) args.get("b")).doubleValue()))
        .tool("boom", "Always fails", McpServer.objectSchema(Map.of()),
            args -> { throw new IllegalStateException("kaboom"); });

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> response) {
        return (Map<String, Object>) response.get("result");
    }

    private Map<String, Object> rpc(Object id, String method, Map<String, Object> params) {
        return params == null
            ? server.handle(Map.of("jsonrpc", "2.0", "id", id, "method", method))
            : server.handle(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    }

    @Test
    void initializeReportsServerInfoAndToolsCapability() {
        Map<String, Object> result = result(rpc(1, "initialize",
            Map.of("protocolVersion", McpServer.PROTOCOL_VERSION)));
        assertThat(result).containsEntry("protocolVersion", McpServer.PROTOCOL_VERSION);
        assertThat((Map<String, Object>) result.get("serverInfo"))
            .containsEntry("name", "calc").containsEntry("version", "1.0.0");
        assertThat((Map<String, Object>) result.get("capabilities")).containsKey("tools");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolsListReturnsEveryRegisteredToolWithSchema() {
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result(rpc(2, "tools/list", null)).get("tools");
        assertThat(tools).extracting(t -> t.get("name")).containsExactly("add", "boom");
        Map<String, Object> add = tools.get(0);
        assertThat(add).containsEntry("description", "Add two numbers");
        assertThat((Map<String, Object>) add.get("inputSchema")).containsEntry("type", "object");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolsCallRunsTheHandlerAndReturnsTextContent() {
        Map<String, Object> result = result(rpc(3, "tools/call",
            Map.of("name", "add", "arguments", Map.of("a", 2, "b", 3))));
        assertThat(result).containsEntry("isError", false);
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0)).containsEntry("type", "text").containsEntry("text", "5.0");
    }

    @Test
    void aFailingToolIsReportedAsAnErrorResultNotAProtocolError() {
        Map<String, Object> result = result(rpc(4, "tools/call",
            Map.of("name", "boom", "arguments", Map.of())));
        assertThat(result).containsEntry("isError", true);
    }

    @Test
    void unknownToolYieldsAnErrorResult() {
        Map<String, Object> result = result(rpc(5, "tools/call", Map.of("name", "nope")));
        assertThat(result).containsEntry("isError", true);
    }

    @Test
    void pingReturnsAnEmptyResult() {
        assertThat(result(rpc(6, "ping", null))).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownMethodIsAJsonRpcMethodNotFound() {
        Map<String, Object> response = rpc(7, "does/not/exist", null);
        assertThat((Map<String, Object>) response.get("error")).containsEntry("code", -32601);
    }

    @Test
    void notificationsProduceNoResponse() {
        assertThat(server.handle(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")))
            .isNull();
    }
}
