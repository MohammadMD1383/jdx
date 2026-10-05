package dev.jdx.site

/**
 * Checks every `jdx ...` mention in a doc's code (inline spans and code-block lines) against
 * the real CLI: the command must exist and every `--flag` must be one that command (or the
 * root) accepts. This is what catches prose like "pass `jdx members --inheritted`" or a
 * command renamed in code but not in the docs.
 *
 * Deliberately lenient where docs legitimately abbreviate: placeholders (`<type>`, `[...]`),
 * `a|b` alternatives and bare `jdx` are skipped.
 */
class DocLint(private val facts: CliFacts) {
    private val groupCommands: Set<String> = facts.commands.filter { it.subcommands.isNotEmpty() }.map { it.name }.toSet()

    /** Problems found in [snippets] (empty = clean). [location] prefixes each message. */
    fun check(snippets: List<String>, location: String): List<String> {
        val problems = mutableListOf<String>()
        for (snippet in snippets) {
            val line = snippet.trim().removePrefix("$ ").trim()
            if (!line.startsWith("jdx ")) continue
            val words = runCatching { ShellWords.split(line) }.getOrElse { continue }
                .takeWhile { !it.startsWith("\u0000") } // stop at a pipe/redirect
                .drop(1)
            val (commandPath, rest) = commandPath(words) ?: continue
            if (commandPath.isEmpty()) continue
            val help = facts.command(commandPath)
            if (help == null) {
                problems += "$location: `$snippet` names unknown command `jdx ${commandPath.joinToString(" ")}`"
                continue
            }
            val accepted = help.flagNames + facts.globalFlags
            for (word in rest) {
                if (!word.startsWith("--")) continue
                val flag = word.substringBefore('=')
                if (flag == "--" || '|' in flag || '<' in flag || '[' in flag) continue
                if (flag !in accepted) {
                    problems += "$location: `$snippet` uses `$flag`, which `jdx ${help.name} --help` does not list"
                }
            }
        }
        return problems
    }

    /** Splits words after `jdx` into (command path, remaining words); null = not checkable. */
    private fun commandPath(words: List<String>): Pair<List<String>, List<String>>? {
        var index = 0
        // Global options may precede the command: `jdx -w fx usages ...`, `jdx --json show ...`.
        while (index < words.size && words[index].startsWith("-")) {
            index += if (words[index] in setOf("-w", "--workspace")) 2 else 1
        }
        val first = words.getOrNull(index) ?: return null
        if (!first.first().isLetter() || first.any { it in "<>[]|…." }) return null
        if (first !in facts.commandNames) return listOf(first) to emptyList()
        val second = words.getOrNull(index + 1)
        if (first in groupCommands && second != null && second.first().isLetter() && second.none { it in "<>[]|…" }) {
            return listOf(first, second) to words.drop(index + 2)
        }
        return listOf(first) to words.drop(index + 1)
    }
}
