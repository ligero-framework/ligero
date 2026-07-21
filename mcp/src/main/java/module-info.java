/**
 * Ligero MCP: expose application tools to LLMs as a Model Context Protocol
 * server over JSON-RPC 2.0, served through a normal Ligero route.
 */
module com.ligero.mcp {
    requires transitive com.ligero.core;
    requires org.slf4j;

    exports com.ligero.mcp;
}
