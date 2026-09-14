package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.render.renderText
import dev.jdx.cli.render.toJson
import dev.jdx.cli.service.DoctorEnvironment
import dev.jdx.cli.service.DoctorService
import dev.jdx.cli.service.exitCodeFor
import kotlin.system.exitProcess

/**
 * `jdx doctor [--json]`. A thin adapter (D-004): asks [DoctorService] for the report, renders
 * it, and maps the outcome to an exit code — 0 when no check failed, 6 on any FAIL (D-015).
 *
 * The service is constructor-injected (default: the real environment) so tests can probe
 * arbitrary environments without spawning processes or touching the real home directory.
 */
class DoctorCommand(
    private val service: DoctorService = DoctorService(DoctorEnvironment.system()),
) : CoreCliktCommand(name = "doctor") {
    override fun help(context: Context): String =
        "Check the jdx environment and report one ok/warn/fail row per check " +
            "(JDK, javap, cache, config, index, Kotlin module, daemon, workspace). " +
            "Exits 6 if any check fails. With --json, print the same report in the JSON envelope."

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val report = service.probe()
        if (effectiveJson(json)) {
            println(report.toJson())
        } else {
            println(report.renderText())
        }
        // Exit non-zero only; a normal return already means 0. (An unconditional
        // exitProcess — even exitProcess(0) — would kill the host JVM, which makes this
        // command untestable in-process and would surprise embedders.)
        val code = exitCodeFor(report)
        if (code != 0) {
            exitProcess(code)
        }
    }
}
