package dev.jdx.cli

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import dev.jdx.cli.commands.DoctorCommand
import dev.jdx.cli.commands.VersionCommand

/**
 * Root of the `jdx` command tree. A thin adapter (D-004): it wires options and subcommands
 * only — every behaviour a subcommand exposes lives in `core`/`index`/`sources`/`decompile`
 * behind `JdxService`. Subcommands register here as their milestones land.
 *
 * Extends Clikt's plain `CoreCliktCommand` (no mordant/JNA — see cli/build.gradle.kts).
 */
class JdxCli : CoreCliktCommand(name = "jdx") {
    init {
        versionOption(BuildInfo.version)
    }

    val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, " +
            "D-007). Accepted before or after the subcommand name (D-027).",
    ).flag()

    override fun run() = Unit
}

/**
 * Effective `--json`: the command's own flag or the root's. `--json` is accepted in both
 * positions (`jdx --json doctor`, `jdx doctor --json`); per-command flags are the mechanism
 * until true global-flag plumbing lands with the T-011 flag pass (D-027).
 */
internal fun CoreCliktCommand.effectiveJson(ownJson: Boolean): Boolean =
    ownJson || (currentContext.parent?.command as? JdxCli)?.json == true

/** One-shot CLI entry point; the fat jar's `Main-Class` (see `app/build.gradle.kts`). */
fun main(args: Array<String>): Unit =
    JdxCli().subcommands(VersionCommand(), DoctorCommand()).main(args)
