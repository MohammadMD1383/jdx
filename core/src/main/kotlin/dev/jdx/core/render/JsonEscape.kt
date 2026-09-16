package dev.jdx.core.render

/**
 * JSON string quoting for the hand-rolled envelope (T-010, D-028).
 *
 * `core` stays dependency-free (no kotlinx-serialization), so JSON is built with
 * string code — the same reason descriptor parsing lives here (pure string work).
 * The cli tier-2 suite parses our output with kotlinx.serialization as the outside
 * check that every byte below is what a real parser expects.
 */
public object JsonEscape {
    /**
     * Quotes [value] as a JSON string: surrounding quotes, `"`/`\` escaped, control
     * characters as short escapes (`\n`, `\t`, `\r`, `\b`, `\u0012`) or `\u00xx`.
     * Non-ASCII prints literally (JSON is UTF-8) to keep goldens readable.
     */
    public fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (char < ' ') {
                    append("\\u" + char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }
}
