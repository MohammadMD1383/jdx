package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/** Default cap on shown source lines; the footer names `--max-lines` to see more. */
public const val DEFAULT_SOURCE_MAX_LINES: Int = 200

/**
 * The answer to a `jdx source` query (T-023, PROPOSAL.md §7.1): one type's
 * verbatim source text with 1-based line numbers — the one result model both
 * renderers read, mirroring [BodyBlock].
 *
 * Sources are ground-truth bytes read from the paired `.java` file (T-021):
 * never pretty-printed, never reflowed. [startLine]/[endLine] are the logical
 * 1-based inclusive range (the whole file, the `--lines` window, or the
 * `--around` member's range — the provenance range); [displayStartLine] +
 * [lines] are the shown slice after context expansion and `--max-lines`
 * truncation. With `--line-numbers` each shown line is prefixed `NNN | ` in
 * text; JSON always carries the bare `text` plus the numeric ranges, so
 * text⊆JSON (D-007) holds either way.
 */
public data class SourceBlock(
    public val canonicalRef: String,
    public val declaringType: String,
    public val file: String,
    public val startLine: Int,
    public val endLine: Int,
    public val displayStartLine: Int,
    public val lines: List<String>,
    public val lineNumbers: Boolean,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /** Text layout per PROPOSAL.md §8.1: ref header, source line, verbatim text, next hint. */
    public fun renderText(color: Boolean = false): String {
        val out = mutableListOf(canonicalRef)
        out.add("  source: ${provenance.firstOrNull()?.artifact ?: "?"} · $file:$startLine-$endLine")
        lines.forEachIndexed { index, line ->
            out.add(if (lineNumbers) "${displayStartLine + index} | $line" else line)
        }
        truncation?.let { out.add("${it.shown} of ${it.total} lines shown (${it.hint} to see more)") }
        for (warning in warnings) out.add("warning ${warning.code}: ${warning.message}")
        out.add("next: jdx show $declaringType")
        val plain = out.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    /** Full JSON envelope; every text fact appears here structurally (D-007). */
    public fun toJson(command: String): String {
        val resultJson = buildString {
            append("{\"ref\":").append(JsonEscape.quote(canonicalRef))
            append(",\"declaring\":").append(JsonEscape.quote(declaringType))
            append(",\"file\":").append(JsonEscape.quote(file))
            append(",\"lines\":[").append(startLine).append(",").append(endLine).append("]")
            append(",\"displayLines\":[").append(displayStartLine).append(",")
                .append(displayStartLine + lines.size - 1).append("]")
            if (lineNumbers) append(",\"lineNumbers\":true")
            append(",\"text\":").append(JsonEscape.quote(lines.joinToString("\n")))
            append("}")
        }
        return envelopeJson(
            ok = true,
            command = command,
            query = canonicalRef,
            resultJson = resultJson,
            truncation = truncation,
            warnings = warnings,
            provenance = provenance,
        )
    }
}

/**
 * Pure display slicing for [SourceBlock]: expands the logical 1-based
 * inclusive `[startLine, endLine]` range by [contextLines] each side over
 * [fileLines] (clamped to the file), then caps to [maxLines] whole lines from
 * the top. Returns the 1-based display start, the shown lines, and the
 * [Truncation] (`null` when the context window fits — an exact fit is not
 * truncation). A negative context or limit behaves as zero.
 */
public fun sliceSourceLines(
    fileLines: List<String>,
    startLine: Int,
    endLine: Int,
    contextLines: Int,
    maxLines: Int,
): Triple<Int, List<String>, Truncation?> {
    if (fileLines.isEmpty()) return Triple(1, emptyList(), null)
    val context = contextLines.coerceAtLeast(0)
    val windowStart = (startLine - context).coerceAtLeast(1)
    val windowEnd = (endLine + context).coerceAtMost(fileLines.size)
    if (windowStart > windowEnd) return Triple(windowStart, emptyList(), null)
    val window = fileLines.subList(windowStart - 1, windowEnd)
    val effectiveMax = maxLines.coerceAtLeast(0)
    if (window.size <= effectiveMax) return Triple(windowStart, window, null)
    return Triple(
        windowStart,
        window.take(effectiveMax),
        Truncation(shown = effectiveMax, total = window.size, hint = "--max-lines ${window.size}"),
    )
}

/**
 * Builds the block from a sliced source file: [fileLines] is the whole source
 * file split on newlines, `[startLine, endLine]` the logical 1-based inclusive
 * range (whole file, `--lines` window, or the `--around` member's range).
 * Context expansion and the line cap run through [sliceSourceLines];
 * provenance carries the logical range (not the context window).
 */
public fun buildSourceBlock(
    canonicalRef: String,
    declaringType: String,
    file: String,
    fileLines: List<String>,
    startLine: Int,
    endLine: Int,
    provenance: List<Provenance>,
    warnings: List<Warning> = emptyList(),
    contextLines: Int = 0,
    lineNumbers: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
): SourceBlock {
    val (displayStart, shown, truncation) = sliceSourceLines(fileLines, startLine, endLine, contextLines, maxLines)
    return SourceBlock(
        canonicalRef = canonicalRef,
        declaringType = declaringType,
        file = file,
        startLine = startLine,
        endLine = endLine,
        displayStartLine = displayStart,
        lines = shown,
        lineNumbers = lineNumbers,
        truncation = truncation,
        warnings = warnings,
        provenance = provenance,
    )
}
