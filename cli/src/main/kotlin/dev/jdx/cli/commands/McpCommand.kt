package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.jdx.cli.BuildInfo
import dev.jdx.mcp.McpSession
import dev.jdx.mcp.serveMcpStdioBlocking

/**
 * `jdx mcp`. A thin adapter (D-004): parses the workspace flag, builds the
 * MCP session over it, and serves JSON-RPC over stdio until the input closes.
 * Behaviour lives in [McpSession] and `JdxService` behind it — this command
 * only wires and blocks.
 */
class McpCommand : CoreCliktCommand(name = "mcp") {
    override fun help(context: Context): String =
        "Serve the MCP stdio server: each jdx query as a typed jdx_* tool with a generated " +
            "schema, for agents that speak Model Context Protocol. Holds the workspace in " +
            "memory for the session, so repeated queries are instant. Tool calls may pass jars, " +
            "coord, or discover when no stored workspace exists. MCP clients spawn this " +
            "as a child process and talk JSON-RPC over stdin/stdout. This server never downloads."

    private val workspace by option(
        "-w",
        "--workspace",
        help = "Stored workspace the server answers from (default: default). " +
            "A missing workspace reads as an error naming `jdx ws` unless the call passes " +
            "jars, coord, or discover, or this server was started with --discover. " +
            "Individual tool calls may override it per call.",
    ).default("default")

    private val discover by option(
        "--discover",
        help = "When the selected workspace does not exist, auto-discover a Gradle or Maven " +
            "project from this process's working directory. Never runs Gradle or Maven. " +
            "A workspace that exists still wins. Tool calls may pass discover, jars, or coord. " +
            "Coordinates resolve from local caches only; this server never downloads.",
    ).flag()

    override fun run() {
        serveMcpStdioBlocking(
            McpSession(
                workspace = workspace,
                appVersion = BuildInfo.version,
                discoverByDefault = discover,
            ),
            BuildInfo.version,
        )
    }
}
