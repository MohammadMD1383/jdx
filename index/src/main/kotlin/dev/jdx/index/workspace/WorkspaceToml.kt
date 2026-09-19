package dev.jdx.index.workspace

/**
 * Minimal TOML codec for [WorkspaceDefinition] files (T-015).
 *
 * This is deliberately *not* a general TOML library: the workspace file is ours, the schema
 * is three keys, and a hand-rolled codec keeps `index` free of a new dependency while staying
 * byte-deterministic (sorted keys, fixed order — the D-007 promise covers config too).
 * If the schema grows past simple scalars/arrays of strings, adopt a real TOML library
 * instead of extending this parser.
 *
 * File shape (written by [encode], read by [decode]):
 *
 * ```toml
 * # Managed by `jdx ws`. Human-editable: keep `name` equal to the file name.
 * name = "mc"
 * jars = ["~/.gradle/caches/**/*.jar", "./build/classes/java/main"]
 * coords = ["com.google.code.gson:gson:2.14.0"]
 * repos = ["https://repo.example.com/maven2"]
 * include_jdk = true
 * ```
 *
 * Rules: `#` starts a comment outside a string; blank lines ignored; `include_jdk` accepts
 * the `includeJdk` camelCase spelling as an alias for hand-edits; `coords` (T-019) is
 * optional and defaults to empty, so pre-coords files still decode; `repos` (T-069) is
 * optional and defaults to empty, so pre-repos files still decode; unknown keys are
 * rejected (a typoed key silently doing nothing would mislead worse than an error);
 * a `name` that disagrees with the file stem is rejected — the stem is the identity.
 */
public object WorkspaceToml {

    /** Encodes [definition] deterministically: fixed key order, one entry per line. */
    public fun encode(definition: WorkspaceDefinition): String = buildString {
        appendLine("# Managed by `jdx ws`. Human-editable: keep `name` equal to the file name.")
        appendLine("name = ${quote(definition.name)}")
        append("jars = [")
        definition.jars.forEachIndexed { index, jar ->
            if (index > 0) append(", ")
            append(quote(jar))
        }
        appendLine("]")
        append("coords = [")
        definition.coords.forEachIndexed { index, coord ->
            if (index > 0) append(", ")
            append(quote(coord))
        }
        appendLine("]")
        append("repos = [")
        definition.repos.forEachIndexed { index, repo ->
            if (index > 0) append(", ")
            append(quote(repo))
        }
        appendLine("]")
        appendLine("include_jdk = ${if (definition.includeJdk) "true" else "false"}")
    }

    /**
     * Decodes [text] from [fileName] (e.g. `mc.toml`). Returns the definition or a
     * human-readable error naming the offending line — never throws on malformed input.
     */
    public fun decode(text: String, fileName: String): Result<WorkspaceDefinition> {
        val stem = fileName.removeSuffix(".toml")
        val stemError = validateWorkspaceName(stem)
        if (stemError != null) return Result.failure(IllegalArgumentException("invalid workspace file name '$fileName': $stemError"))
        var name: String? = null
        var jars: List<String>? = null
        var coords: List<String>? = null
        var repos: List<String>? = null
        var includeJdk: Boolean? = null
        val seen = mutableSetOf<String>()
        text.lines().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) return@forEachIndexed
            val equals = line.indexOf('=')
            if (equals < 0) {
                return Result.failure(IllegalArgumentException("$fileName:$lineNumber: expected `key = value`, got '$line'"))
            }
            val key = line.substring(0, equals).trim()
            val value = line.substring(1 + equals).trim()
            // `includeJdk` is accepted as an alias for hand-edits; normalise before the
            // duplicate check so the two spellings in one file read as a duplicate key.
            val canonicalKey = if (key == "includeJdk") "include_jdk" else key
            if (!seen.add(canonicalKey)) {
                return Result.failure(IllegalArgumentException("$fileName:$lineNumber: duplicate key '$key'"))
            }
            when (canonicalKey) {
                "name" -> {
                    name = parseString(value)
                        ?: return Result.failure(IllegalArgumentException("$fileName:$lineNumber: `name` must be a quoted string"))
                }
                "jars" -> {
                    jars = parseStringArray(value)
                        ?: return Result.failure(IllegalArgumentException("$fileName:$lineNumber: `jars` must be an array of quoted strings"))
                }
                "coords" -> {
                    coords = parseStringArray(value)
                        ?: return Result.failure(IllegalArgumentException("$fileName:$lineNumber: `coords` must be an array of quoted strings"))
                }
                "repos" -> {
                    repos = parseStringArray(value)
                        ?: return Result.failure(IllegalArgumentException("$fileName:$lineNumber: `repos` must be an array of quoted strings"))
                }
                "include_jdk" -> {
                    includeJdk = when (value) {
                        "true" -> true
                        "false" -> false
                        else -> return Result.failure(
                            IllegalArgumentException("$fileName:$lineNumber: `include_jdk` must be true or false"),
                        )
                    }
                }
                else -> return Result.failure(IllegalArgumentException("$fileName:$lineNumber: unknown key '$key'"))
            }
        }
        val declared = name ?: return Result.failure(IllegalArgumentException("$fileName: missing required key `name`"))
        if (declared != stem) {
            return Result.failure(
                IllegalArgumentException("$fileName: `name` is \"$declared\" but the file is named '$fileName' — they must match"),
            )
        }
        return Result.success(
            WorkspaceDefinition(
                name = stem,
                jars = jars ?: emptyList(),
                includeJdk = includeJdk ?: true,
                coords = coords ?: emptyList(),
                repos = repos ?: emptyList(),
            ),
        )
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    /** Strips a `#` comment, honouring double-quoted strings and backslash escapes. */
    internal fun stripComment(line: String): String {
        var inString = false
        var escaped = false
        for (index in line.indices) {
            val char = line[index]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                char == '#' && !inString -> return line.substring(0, index)
            }
        }
        return line
    }

    private fun parseString(value: String): String? {
        if (value.length < 2 || !value.startsWith('"') || !value.endsWith('"')) return null
        val out = StringBuilder()
        var index = 1
        while (index < value.length - 1) {
            val char = value[index]
            if (char != '\\') {
                out.append(char)
                index++
                continue
            }
            index++
            if (index >= value.length - 1) return null
            when (val escape = value[index]) {
                '"', '\\' -> out.append(escape)
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (index + 4 >= value.length) return null
                    val code = value.substring(index + 1, index + 5).toIntOrNull(16) ?: return null
                    out.append(code.toChar())
                    index += 4
                }
                else -> return null
            }
            index++
        }
        return out.toString()
    }

    private fun parseStringArray(value: String): List<String>? {
        val trimmed = value.trim()
        if (!trimmed.startsWith('[') || !trimmed.endsWith(']')) return null
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        val items = splitArray(inner) ?: return null
        val result = ArrayList<String>(items.size)
        for (item in items) {
            result.add(parseString(item.trim()) ?: return null)
        }
        return result
    }

    /** Splits a `[...]` body on top-level commas, honouring strings and escapes. Null when unterminated. */
    private fun splitArray(inner: String): List<String>? {
        val items = mutableListOf<String>()
        var inString = false
        var escaped = false
        var depth = 0
        var start = 0
        for (index in inner.indices) {
            val char = inner[index]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == '[' -> depth++
                !inString && char == ']' -> {
                    if (depth == 0) return null
                    depth--
                }
                !inString && depth == 0 && char == ',' -> {
                    items.add(inner.substring(start, index))
                    start = index + 1
                }
            }
        }
        if (inString || escaped || depth != 0) return null
        items.add(inner.substring(start))
        return items
    }
}
