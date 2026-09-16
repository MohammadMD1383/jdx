package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import dev.jdx.cli.effectiveJson
import kotlin.system.exitProcess

/**
 * `jdx show <type>`. A thin adapter (D-004): parses roots/output flags, asks [JdxService]
 * for the class card, renders it, and maps the outcome to an exit code (D-015).
 *
 * The card answers "what is this type" — kind, declaration, supertypes, member counts —
 * and always ends with the copy-pasteable `next:` hint for `members`.
 */
class ShowCommand(
    private val query: ShowQuery = ::defaultShowQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "show") {
    override fun help(context: Context): String =
        "Show the class card for a type: kind, modifiers, supertypes, member counts, " +
            "provenance and the next command to run. Takes a type reference " +
            "(e.g. 'com.example.Point', 'Gson', 'java.util.Map\$Entry'); " +
            "member references are a usage error. " +
            "Reads --jars roots plus the JDK stdlib unless --no-jdk. " +
            "Exits 1 when the type is unknown, 2 when a short name is ambiguous."

    private val ref by argument(help = "Type reference to show (full, short or nested form).")

    private val jars by option(
        "--jars",
        help = "Binary roots: jar files, class directories or globs (repeatable).",
    ).multiple()

    private val noJdk by option(
        "--no-jdk",
        help = "Do not include the running JDK's stdlib (included by default).",
    ).flag()

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    private val noColor by option(
        "--no-color",
        help = "Disable ANSI colors even on a TTY (piped output is always plain).",
    ).flag()

    override fun run() {
        val outcome = query(ref, ReadCommandSupport.rootsOf(jars, noJdk))
        ReadCommandSupport.finish(outcome, "show", effectiveJson(json), noColor, terminate)
    }
}
