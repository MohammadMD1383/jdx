package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.options.option
import dev.jdx.cli.BuildInfo
import dev.jdx.server.DEFAULT_HTTP_BIND
import dev.jdx.server.DEFAULT_HTTP_PORT
import dev.jdx.server.JdxHttpServer
import kotlin.system.exitProcess

/**
 * `jdx serve` (T-044; PROPOSAL.md §14.4).
 *
 * Serves the HTTP/JSON API in the foreground: `GET /v1/<command>?…`,
 * `POST /v1/batch`, `GET /v1/health` over one stored workspace, bound to
 * localhost by default. A thin adapter (D-004): flag parsing and exit codes
 * live here, every answer lives in `:server` + `JdxService` behind it.
 *
 * Exits: 0 serving until interrupted · 3 usage error (bad `--port`/`--bind`)
 * · 6 bind/IO failure.
 */
class ServeCommand(
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val serve: (bind: String, port: Int, workspace: String) -> Unit = ::serveBlocking,
) : CoreCliktCommand(name = "serve") {
    override fun help(context: Context): String =
        "Serve the HTTP/JSON API: GET /v1/<command>?query=…&<param>=… for each jdx query, " +
            "POST /v1/batch for NDJSON batches, GET /v1/health as a liveness probe. Answers " +
            "are the exact --json envelopes over one stored workspace (default: default), " +
            "bound to localhost by default. Blocks until interrupted."

    private val workspace by option(
        "-w",
        "--workspace",
        help = "Stored workspace the server answers from (default: default). " +
            "Individual requests may override it per call with ?workspace=.",
    ).default("default")

    private val port by option(
        "--port",
        help = "Port to listen on (default $DEFAULT_HTTP_PORT).",
    ).int().default(DEFAULT_HTTP_PORT)

    private val bind by option(
        "--bind",
        help = "Address to bind (default $DEFAULT_HTTP_BIND; localhost only unless changed).",
    ).default(DEFAULT_HTTP_BIND)

    override fun run() {
        if (port !in 1..65535) {
            println("usage error: invalid --port '$port': use 1-65535")
            terminate(3)
            return
        }
        if (bind.isBlank()) {
            println("usage error: invalid --bind: an address is required")
            terminate(3)
            return
        }
        try {
            serve(bind, port, workspace)
        } catch (e: Exception) {
            println("cannot serve: ${e.message}")
            terminate(6)
        }
    }
}

/** Binds the production server and blocks until interrupted (the injectable [ServeCommand.serve]). */
private fun serveBlocking(bind: String, port: Int, workspace: String) {
    val server = JdxHttpServer(workspace = workspace, bind = bind, port = port, appVersion = BuildInfo.version)
    Runtime.getRuntime().addShutdownHook(Thread({ server.stop() }, "jdx-serve-shutdown"))
    server.start()
    val address = server.localAddress()
    System.err.println("serving workspace '$workspace' on http://${address.hostString}:${address.port}/v1/")
    server.join()
}
