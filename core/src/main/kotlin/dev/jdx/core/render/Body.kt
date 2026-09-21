package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/** Default cap on shown body lines; the footer names `--max-lines` to see more. */
public const val DEFAULT_BODY_MAX_LINES: Int = 200

/**
 * The answer to a `jdx body` query (T-022, PROPOSAL.md §7.1): one member's verbatim
 * source slice with 1-based line numbers — the one result model both renderers read,
 * mirroring [MemberListing] and [ClassCard].
 *
 * Bodies are ground-truth bytes sliced from `.java` source text (T-021): never
 * pretty-printed, never reflowed. [startLine]/[endLine] are the member's own 1-based
 * inclusive range (the provenance range); [displayStartLine] + [lines] are the shown
 * slice after `--context` expansion and `--max-lines` truncation. With
 * `--line-numbers` each shown line is prefixed `NNN | ` in text; JSON always carries
 * the bare `text` plus the numeric ranges, so text⊆JSON (D-007) holds either way.
 * [signature] is the `--with-signature` header (T-024): the resolved bytecode
 * signature line, or `null` when the flag is off. [doc] is the `--with-doc`
 * member doc (T-072): rendered plain-text lines, or `null` when the flag is
 * off or no doc exists — so pre-flag goldens stay byte-identical.
 */
public data class BodyBlock(
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
    public val signature: String? = null,
    public val doc: List<String>? = null,
) {
    /** Text layout per PROPOSAL.md §8.1: ref header, source line, verbatim body, next hint. */
    public fun renderText(color: Boolean = false): String {
        val out = mutableListOf(canonicalRef)
        out.add("  " + renderSourceLine(provenance.firstOrNull(), file, startLine, endLine))
        signature?.let { out.add("  signature: $it") }
        if (doc != null) {
            out.add("  doc:")
            doc.forEach { out.add("    $it") }
        }
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
            signature?.let { append(",\"signature\":").append(JsonEscape.quote(it)) }
            if (doc != null) append(",\"doc\":").append(JsonEscape.quote(doc.joinToString("\n")))
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
 * Pure display slicing for [BodyBlock]: expands the body's 1-based inclusive
 * `[startLine, endLine]` range by [contextLines] each side over [fileLines] (clamped
 * to the file), then caps to [maxLines] whole lines from the top. Returns the
 * 1-based display start, the shown lines, and the [Truncation] (`null` when the
 * context window fits — an exact fit is not truncation). A negative context or
 * limit behaves as zero.
 */
public fun sliceBodyLines(
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
 * Builds the block from a sliced body: [fileLines] is the whole source file split
 * on newlines, `[startLine, endLine]` the member's 1-based inclusive range from the
 * T-021 AST slice. Context expansion and the line cap run through [sliceBodyLines];
 * provenance carries the member range (not the context window).
 */
public fun buildBodyBlock(
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
    signature: String? = null,
    doc: List<String>? = null,
): BodyBlock {
    val (displayStart, shown, truncation) = sliceBodyLines(fileLines, startLine, endLine, contextLines, maxLines)
    return BodyBlock(
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
        signature = signature,
        doc = doc,
    )
}
