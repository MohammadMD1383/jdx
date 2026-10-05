package dev.jdx.site

/** HTML escaping and the small reusable fragments every page shares. */
object Html {
    fun text(value: String): String = buildString(value.length) {
        for (char in value) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(char)
            }
        }
    }

    fun attr(value: String): String = text(value).replace("\"", "&quot;")

    /**
     * A code block with a copy button. `console` blocks split into prompt, command and output
     * spans so the copy button copies only what a reader should type.
     */
    fun codeBlock(literal: String, language: String, executed: Boolean): String {
        val body = literal.trimEnd('\n')
        val code = if (language == "console") consoleLines(body) else text(body)
        val languageClass = if (language.isNotEmpty()) " class=\"language-${attr(language)}\"" else ""
        val caption = if (executed) {
            "<p class=\"code-caption\">Real output — this example ran against the current jdx when the page was built.</p>"
        } else {
            ""
        }
        return "<div class=\"code\"><button class=\"copy\" type=\"button\">Copy</button>" +
            "<pre tabindex=\"0\"><code$languageClass>$code</code></pre>$caption</div>\n"
    }

    private fun consoleLines(body: String): String = body.lines().joinToString("\n") { line ->
        when {
            line.startsWith("$ ") ->
                "<span class=\"prompt\" aria-hidden=\"true\">$ </span><span class=\"cmd\">${text(line.removePrefix("$ "))}</span>"
            Regex("""^\[exit \d+]$""").matches(line) -> "<span class=\"exit\">${text(line)}</span>"
            else -> "<span class=\"out\">${text(line)}</span>"
        }
    }

    /** Inline-code-aware rendering for short trusted strings like `jdx members` labels. */
    fun inlineCode(value: String): String =
        value.split('`').mapIndexed { index, part -> if (index % 2 == 1) "<code>${text(part)}</code>" else text(part) }
            .joinToString("")
}

/** Minimal JSON writer for JSON-LD. Deterministic key order. */
object JsonWriter {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (key, entry) -> quote(key.toString()) + ":" + encode(entry) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> quote(value.toString())
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (char in value) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char == '<' -> append("\\u003c") // never let "</script>" close the JSON-LD tag
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }
}
