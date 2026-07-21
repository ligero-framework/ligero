package com.ligero.mcp;

import com.ligero.test.LigeroTest;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end over the Streamable-HTTP transport: a JSON-RPC POST to the mounted
 * MCP endpoint returns the JSON-RPC response through the real server stack.
 */
class McpHttpTest {

    private LigeroTest mcpApp() {
        return LigeroTest.create(app -> app.use(
            McpServer.create("calc", "1.0.0")
                .tool("add", "Add two integers",
                    McpServer.objectSchema(Map.of(
                        "a", McpServer.numberParam("first"),
                        "b", McpServer.numberParam("second")), "a", "b"),
                    args -> String.valueOf(
                        ((Number) args.get("a")).intValue() + ((Number) args.get("b")).intValue()))
                .http("/mcp")));
    }

    @Test
    void toolCallOverHttpReturnsTheResult() {
        try (LigeroTest test = mcpApp()) {
            LigeroTest.TestResponse response = test.post("/mcp").json("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"add","arguments":{"a":2,"b":3}}}
                """).execute();

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body()).contains("\"text\":\"5\"").contains("\"isError\":false");
        }
    }

    @Test
    void initializeOverHttpReturnsServerInfo() {
        try (LigeroTest test = mcpApp()) {
            LigeroTest.TestResponse response = test.post("/mcp")
                .json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                .execute();
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body()).contains("\"name\":\"calc\"").contains("protocolVersion");
        }
    }

    @Test
    void aNotificationIsAcceptedWithNoBody() {
        try (LigeroTest test = mcpApp()) {
            LigeroTest.TestResponse response = test.post("/mcp")
                .json("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .execute();
            assertThat(response.status()).isEqualTo(202);
        }
    }
}
