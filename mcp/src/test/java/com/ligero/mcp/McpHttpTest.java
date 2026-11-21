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

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

    @Test
    void foreignBrowserOriginsAreRejected() {
        // DNS-rebinding defence: a page on another site must not drive a local MCP server
        try (LigeroTest test = mcpApp()) {
            LigeroTest.TestResponse response = test.post("/mcp")
                .header("Origin", "https://evil.example").json(PING).execute();
            assertThat(response.status()).isEqualTo(403);
        }
    }

    @Test
    void loopbackAndAllowListedOriginsAreAccepted() {
        try (LigeroTest test = LigeroTest.create(app -> app.use(
                McpServer.create("calc", "1.0.0").allowedOrigins("https://app.example.com/").http("/mcp")))) {
            for (String origin : new String[] {
                "http://localhost:5173", "http://127.0.0.1:8080", "http://[::1]:3000", "https://APP.example.com"}) {
                assertThat(test.post("/mcp").header("Origin", origin).json(PING).execute().status())
                    .as(origin).isEqualTo(200);
            }
            // look-alike hosts are not loopback
            for (String origin : new String[] {"http://localhost.evil.example", "http://127.0.0.1.evil.example"}) {
                assertThat(test.post("/mcp").header("Origin", origin).json(PING).execute().status())
                    .as(origin).isEqualTo(403);
            }
        }
    }

    @Test
    void internalFailuresDoNotLeakDetails() {
        McpServer server = McpServer.create("x", "1");
        Map<String, Object> response = server.handle(null);
        assertThat(response.get("error").toString()).contains("-32600");
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
