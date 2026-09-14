package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.jdx.cli.BuildInfo
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.render.VersionResult
import dev.jdx.cli.render.renderText
import dev.jdx.cli.render.toJson

/**
 * `jdx version [--json]`. A thin adapter (D-004): formats [VersionResult], prints it, exits 0.
 */
class VersionCommand : CoreCliktCommand(name = "version") {
    override fun help(context: Context): String =
        "Print the jdx version. With --json, print it inside the standard JSON envelope."

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val result = VersionResult(BuildInfo.version)
        if (effectiveJson(json)) {
            println(result.toJson())
        } else {
            println(result.renderText())
        }
    }
}
