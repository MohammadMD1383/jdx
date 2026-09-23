package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.render.helpResult
import dev.jdx.cli.render.renderText
import dev.jdx.cli.render.toJson

/**
 * `jdx help [--agent] [--json]` (T-049, PROPOSAL.md §3.8).
 *
 * A thin adapter (D-004): all content lives in `render.HelpSheet` (one
 * [dev.jdx.cli.render.HELP_ROWS] table feeding both flavours); this only
 * parses flags and prints. Exit 0 always on valid flags; Clikt maps bad
 * flags to exit 3.
 */
class HelpCommand : CoreCliktCommand(name = "help") {
    override fun help(context: Context): String =
        "Print the jdx cheat sheet. With --agent, print the compact paste-ready block " +
            "for CLAUDE.md / system prompts. With --json, print the same rows in the JSON envelope."

    private val agent by option(
        "--agent",
        help = "Print the compact paste-ready cheat sheet for agents instead of the human layout.",
    ).flag()

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val result = helpResult(agent)
        if (effectiveJson(json)) {
            println(result.toJson())
        } else {
            println(result.renderText())
        }
    }
}
