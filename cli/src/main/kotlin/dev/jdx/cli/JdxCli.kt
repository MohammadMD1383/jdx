package dev.jdx.cli

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.versionOption

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

    override fun run() = Unit
}

/** One-shot CLI entry point; the fat jar's `Main-Class` (see `app/build.gradle.kts`). */
fun main(args: Array<String>): Unit = JdxCli().main(args)
