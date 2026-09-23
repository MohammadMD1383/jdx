package dev.jdx.core.render

/**
 * Whole-line text caps for token budgets (T-047, PROPOSAL.md §3.1).
 *
 * `--limit` caps entities; `--max-lines` caps text lines. The cap keeps whole
 * lines only — it never splits an entity mid-line — and always names the
 * continuation, so a cut is never silent (G6). Pure strings: deterministic,
 * never throws, no IO.
 */
public object TokenBudget {

    /**
     * Caps [text] to at most [maxLines] whole lines (universal newlines:
     * `\r\n` and lone `\r` read as line breaks and normalise to `\n`).
     * A single trailing newline terminates the last line; it is not a phantom
     * line. An exact fit is not a cut. A negative budget behaves as zero; an
     * empty text stays empty (there is nothing to omit, so no footer).
     */
    public fun capLines(text: String, maxLines: Int): String {
        if (text.isEmpty()) return text
        val max = maxLines.coerceAtLeast(0)
        val hasTrailingNewline = text.endsWith('\n') || text.endsWith('\r')
        var lines = text.split(Regex("\r\n|\n|\r"))
        if (lines.size > 1 && lines.last().isEmpty()) lines = lines.dropLast(1)
        if (lines.size <= max) {
            if (!text.contains('\r')) return text
            return lines.joinToString("\n") + if (hasTrailingNewline) "\n" else ""
        }
        val kept = lines.take(max)
        val omitted = lines.size - kept.size
        val head = if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n"
        return head + "… $omitted more lines (--max-lines ${lines.size} to see more)"
    }
}
