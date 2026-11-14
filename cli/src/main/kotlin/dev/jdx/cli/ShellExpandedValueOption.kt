package dev.jdx.cli

/**
 * Detects the shell-expanded-glob footgun for single-value classpath flags
 * (`--jars`, `--src`): the shell expands an unquoted `--jars 'lib/' + '*.jar'`
 * style glob into `--jars a.jar b.jar c.jar ...` before jdx sees argv. Clikt
 * takes only the first path, and the rest become "unexpected extra arguments"
 * with no mention of the flag (issue #86).
 *
 * Each occurrence of these flags takes **one** value (repeat the flag, or pass
 * one quoted glob that jdx expands). Two or more consecutive path-like tokens
 * after one occurrence is almost always an unquoted shell glob — report it as a
 * copy-pasteable usage error instead of Clikt's bare Usage dump.
 *
 * Pure argv scan: no IO, no Clikt. Kept out of [JdxService] because this is a
 * shell/CLI concern, not a query concern.
 */
internal object ShellExpandedValueOption {
    /** Flags that take one value per occurrence and accept globs/paths. */
    private val VALUE_OPTIONS = listOf("--jars", "--src")

    /**
     * Returns a `usage error: ...` message when [args] look like a shell-expanded
     * value option, else `null` (let Clikt parse normally).
     */
    fun detect(args: List<String>): String? {
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val option = matchingOption(arg)
            if (option == null) {
                i++
                continue
            }
            if (arg.startsWith("$option=")) {
                // `--jars=path` form cannot be shell-split into multiple values.
                i++
                continue
            }
            val values = mutableListOf<String>()
            var j = i + 1
            while (j < args.size && !isOptionToken(args[j])) {
                values.add(args[j])
                j++
            }
            val pathLikeCount = values.count(::looksLikePathOrGlob)
            if (pathLikeCount >= 2) {
                return "usage error: $option got $pathLikeCount paths; " +
                    "quote the glob to pass it as one value: $option '.../lib/*.jar' " +
                    "(or repeat $option per path)"
            }
            i = j
        }
        return null
    }

    private fun matchingOption(arg: String): String? =
        VALUE_OPTIONS.firstOrNull { option ->
            arg == option || arg.startsWith("$option=")
        }

    /** True for `--flag` / `-x`; false for `--` (end of options) and bare values. */
    private fun isOptionToken(token: String): Boolean =
        token != "--" && token.startsWith("-") && token.length > 1

    /**
     * Heuristic that separates a classpath path/glob from a symbol ref / workspace
     * name sitting after `--jars one.jar SomeType` (legitimate: one value + positional).
     */
    internal fun looksLikePathOrGlob(token: String): Boolean {
        if (token.contains('/') || token.contains('\\')) return true
        if (token.contains('*') || token.contains('?') || token.contains('[')) return true
        val lower = token.lowercase()
        return lower.endsWith(".jar") || lower.endsWith(".zip") || lower.endsWith(".jmod")
    }
}
