package dev.jdx.mcp

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * The MCP stdio server (T-043): JSON-RPC over stdio, one typed tool per
 * [RpcCommand][dev.jdx.core.rpc.RpcCommand].
 *
 * Thin by rule (D-004): this file owns SDK registration and stdio transport
 * only. The tool table lives in [ALL_MCP_TOOLS], answering in [McpSession] —
 * behaviour stays in `JdxService` on the other side of that seam, so an MCP
 * answer is byte-identical to the CLI `--json` answer by construction.
 */
private val structuredJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Registers every [ALL_MCP_TOOLS] entry on a new SDK [Server]. Each handler
 * answers through [session] and returns the `--json` envelope verbatim as
 * text (the parity shape), plus the parsed envelope as structured content so
 * typed clients need not re-parse, plus `isError` for the exit-code branch.
 */
public fun buildMcpServer(session: McpSession, appVersion: String): Server {
    val server = Server(
        Implementation(name = "jdx", version = appVersion),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
        "jdx — an IDE for AI agents: Java/Kotlin structure, sources, usages and " +
            "call graphs, one precise question per tool. Every answer carries provenance; " +
            "ambiguous refs list candidates instead of guessing.",
    )
    for (tool in ALL_MCP_TOOLS) {
        server.addTool(
            name = tool.toolName,
            description = tool.description,
            inputSchema = tool.inputSchema(),
        ) { request ->
            val result = session.callTool(request.name, request.arguments ?: buildJsonObject {})
            CallToolResult(
                content = listOf(TextContent(result.text)),
                isError = result.isError,
                structuredContent = structuredOrNull(result.text),
            )
        }
    }
    return server
}

/** The parsed envelope for typed clients, or `null` when the text is not JSON (never, but total). */
public fun structuredOrNull(text: String): JsonObject? = try {
    structuredJson.parseToJsonElement(text) as? JsonObject
} catch (_: Exception) {
    null
}

/**
 * Serves [server] over stdio until the input closes: the `jdx mcp` process
 * shape MCP clients spawn. Returns when the client goes away (stdin EOF
 * closes the transport) rather than hanging — a lingering server after
 * disconnect would leak a JVM per client session.
 */
public suspend fun runMcpStdio(server: Server) {
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered(),
    ) { }
    val closed = CompletableDeferred<Unit>()
    transport.onClose { closed.complete(Unit) }
    server.createSession(transport)
    closed.await()
    // Bounded: the session is over (stdin EOF closed the transport), so a
    // stuck close must not wedge shutdown — the blocking entry exits below.
    runCatching { withTimeoutOrNull(5_000) { server.close() } }
}

/**
 * Blocking entry for the `jdx mcp` CLI adapter: builds the server from
 * [session] and serves stdio to EOF. Exists so `:cli` never names SDK types
 * or coroutines (D-004) — it calls this one function and blocks.
 *
 * Silences kotlin-logging's startup banner first: it prints to stdout, and on
 * a stdio transport stdout *is* the protocol — one stray line breaks every
 * client. (All further SDK logging routes to slf4j, which has no binding in
 * the fat jar and stays silent.)
 */
public fun serveMcpStdioBlocking(session: McpSession, appVersion: String): Unit {
    kotlinx.coroutines.runBlocking {
        KotlinLoggingConfiguration.logStartupMessage = false
        runMcpStdio(buildMcpServer(session, appVersion))
    }
    // The process exists only to serve: stdin EOF ended the session above, so
    // exit rather than risk lingering on an SDK scope thread past disconnect
    // (seen once in ~20 live runs: stdout closed, JVM stayed — L-102).
    kotlin.system.exitProcess(0)
}
