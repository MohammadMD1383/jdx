package dev.jdx.core.render

/**
 * The ANSI gate (T-010, PROPOSAL.md §8.1): colour only when stdout is a TTY.
 *
 * `core` performs no IO, so it cannot test TTY-ness itself — renderers take a
 * `color` flag (default off) and adapters pass `System.console() != null`
 * (plus `--no-color`). Piped output is therefore always plain text. The scheme
 * is deliberately tiny (bold headers); agents read unstyled text either way.
 */
public object Ansi {
    private const val ESCAPE: String = "\u001B"
    private const val BOLD: String = "$ESCAPE[1m"
    private const val RESET: String = "${ESCAPE}[0m"

    /** Bolds listing headers; every other line passes through untouched. */
    public fun colorizeListing(plain: String): String = plain.lines().joinToString("\n") { line ->
        if (isHeader(line)) "$BOLD$line$RESET" else line
    }

    private fun isHeader(line: String): Boolean =
        line.startsWith("members of ") ||
            (line.endsWith(":") && !line.startsWith(" ")) ||
            line.startsWith("warning ") ||
            line.matches(Regex("\\d+ of \\d+ members shown .*"))
}

/** Strips `ESC[…m` sequences; tests assert `colorizeListing(x).stripAnsi() == x`. */
public fun String.stripAnsi(): String = replace(Regex("\u001B\\[[0-9;]*m"), "")
