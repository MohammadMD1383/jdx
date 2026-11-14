package dev.jdx.core.render

import dev.jdx.core.model.Warning

/**
 * Text-mode warning presentation (issue #86): peel `warning CODE: …` lines out
 * of a rendered answer so adapters can put them on stderr, and cap volume so a
 * workspace full of corrupt Kotlin inlines cannot bury the answer.
 *
 * Renderers still embed warning lines in [MemberListing.renderText] and friends
 * (unit tests and JSON completeness stay on one shape). Adapters call
 * [partition] + [cap] at the print boundary: stdout gets the answer,
 * stderr gets the capped warnings. `--json` never uses this path — the envelope
 * `warnings` array stays complete and uncapped.
 */
public object WarningLines {

    /** Soft cap per [dev.jdx.core.model.WarningCode] on the text/stderr side. */
    public const val DEFAULT_MAX_PER_CODE: Int = 5

    /** Prefix every warning text line starts with (also what [Ansi] bolds). */
    public const val LINE_PREFIX: String = "warning "

    /** One renderer line for [warning], matching the historical text layout. */
    public fun line(warning: Warning): String =
        "$LINE_PREFIX${warning.code}: ${warning.message}"

    /**
     * True when [line] is a warning line after ANSI is stripped. Colourised
     * warm/cold text still peels correctly.
     */
    public fun isWarningLine(line: String): Boolean =
        line.stripAnsi().startsWith(LINE_PREFIX)

    /**
     * Split a full `renderText` / `renderBriefText` blob into the answer body
     * (non-warning lines, ANSI preserved) and the plain warning lines (ANSI
     * stripped). Empty input stays empty; a trailing newline on the answer is
     * preserved only when the original had one *and* at least one answer line
     * remains.
     */
    public fun partition(rendered: String): Partition {
        if (rendered.isEmpty()) return Partition(answer = "", warningLines = emptyList())
        val hasTrailingNewline = rendered.endsWith('\n') || rendered.endsWith('\r')
        var lines = rendered.split(Regex("\r\n|\n|\r"))
        if (lines.size > 1 && lines.last().isEmpty()) lines = lines.dropLast(1)
        val answer = ArrayList<String>(lines.size)
        val warnings = ArrayList<String>()
        for (line in lines) {
            val plain = line.stripAnsi()
            if (plain.startsWith(LINE_PREFIX)) {
                warnings.add(plain)
            } else {
                answer.add(line)
            }
        }
        if (answer.isEmpty()) {
            return Partition(answer = "", warningLines = warnings)
        }
        val body = answer.joinToString("\n")
        return Partition(
            answer = if (hasTrailingNewline) "$body\n" else body,
            warningLines = warnings,
        )
    }

    /** Answer body only — warnings dropped. */
    public fun answerOnly(rendered: String): String = partition(rendered).answer

    /**
     * Keep at most [maxPerCode] warning lines per distinct code (in original
     * order). Overflow for a code becomes one summary line after that code's
     * kept lines, still bearing the `warning CODE:` prefix so agents can grep
     * stderr the same way. Codes never seen do not emit a summary. A
     * non-positive [maxPerCode] keeps nothing but the per-code summaries.
     */
    public fun cap(warningLines: List<String>, maxPerCode: Int = DEFAULT_MAX_PER_CODE): List<String> {
        if (warningLines.isEmpty()) return emptyList()
        val max = maxPerCode.coerceAtLeast(0)
        val keptPerCode = LinkedHashMap<String, Int>()
        val overflowPerCode = LinkedHashMap<String, Int>()
        val out = ArrayList<String>(warningLines.size)
        for (line in warningLines) {
            val code = codeOf(line) ?: continue
            val kept = keptPerCode.getOrDefault(code, 0)
            if (kept < max) {
                out.add(line)
                keptPerCode[code] = kept + 1
            } else {
                overflowPerCode[code] = overflowPerCode.getOrDefault(code, 0) + 1
            }
        }
        // Summaries follow in first-seen code order (LinkedHashMap iteration).
        for ((code, overflow) in overflowPerCode) {
            if (overflow > 0) {
                out.add("$LINE_PREFIX$code: … and $overflow more")
            }
        }
        return out
    }

    /** Write each capped line followed by a newline; no-op when empty. */
    public fun write(warningLines: List<String>, sink: Appendable) {
        for (line in warningLines) {
            sink.append(line).append('\n')
        }
    }

    private fun codeOf(line: String): String? {
        if (!line.startsWith(LINE_PREFIX)) return null
        val rest = line.substring(LINE_PREFIX.length)
        val colon = rest.indexOf(':')
        if (colon <= 0) return null
        return rest.substring(0, colon)
    }

    /** Answer body + peeled warning lines from one rendering. */
    public data class Partition(
        public val answer: String,
        public val warningLines: List<String>,
    )
}
