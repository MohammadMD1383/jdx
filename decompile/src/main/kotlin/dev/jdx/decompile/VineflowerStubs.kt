package dev.jdx.decompile

/**
 * Detects Vineflower "could not decompile" member stubs (issue #86).
 *
 * Vineflower sometimes returns a *successful* reconstruction whose member body
 * is only a `$VF:` comment — commonly a suspend-lambda click handler:
 *
 * ```
 * {
 *    // $VF: Could not decompile lambda - root function was not found. Is this a suspend lambda?
 * }
 * ```
 *
 * That text is useless to an agent (and phrased as a question). Treating it as
 * an engine failure lets the default body/source ladder fall through to
 * `--engine javap`, and forced `--engine vineflower` exits 1 naming the hatch
 * instead of serving the stub as if it were source.
 *
 * Detection is deliberately narrow: a body that also contains real statements
 * keeps its reconstruction even when a `$VF:` comment appears beside them.
 */
public object VineflowerStubs {

    /**
     * Whether [memberText] (one member's sliced source, often including the
     * signature line the Java seam returns) is only a Vineflower failure
     * marker inside its braces — signature, braces and blank lines ignored.
     */
    public fun isFailureStub(memberText: String): Boolean {
        val meaningful = meaningfulLines(braceInterior(memberText))
        if (meaningful.isEmpty()) return false
        return meaningful.all { vfFailurePayload(it) != null }
    }

    /**
     * Short, action-oriented reason for the exit-1 detail line. Prefer
     * `suspend lambda` when Vineflower asked that question; otherwise a
     * compact form of the `$VF:` clause. Never empty when [isFailureStub]
     * is true.
     */
    public fun failureReason(memberText: String): String {
        val comments = meaningfulLines(braceInterior(memberText)).mapNotNull(::vfFailurePayload)
        val joined = comments.joinToString(" ")
        if (joined.contains("suspend lambda", ignoreCase = true)) return "suspend lambda"
        val first = comments.firstOrNull()?.trim().orEmpty()
        if (first.isNotEmpty()) {
            // Drop the trailing question Vineflower sometimes appends — the
            // CLI answers with a next command, it does not ask the agent.
            val clause = first.substringBefore('.').trim()
            if (clause.isNotEmpty()) return clause.replaceFirstChar { it.lowercase() }
        }
        return "could not decompile"
    }

    /**
     * The outermost `{…}` span when present (the method body the Java seam
     * slices together with its signature); otherwise the whole text. Lets a
     * stub check ignore `public … identity(…) {` while still catching the
     * `$VF:` comment inside.
     */
    private fun braceInterior(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return text
        return text.substring(start, end + 1)
    }

    private fun meaningfulLines(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "{" && it != "}" }
            .toList()

    /**
     * The text after `$VF:` for a line- or block-comment failure marker, or
     * null when the line is not such a marker.
     */
    private fun vfFailurePayload(line: String): String? {
        val body = when {
            line.startsWith("//") -> line.removePrefix("//").trim()
            line.startsWith("/*") && line.endsWith("*/") ->
                line.removePrefix("/*").removeSuffix("*/").trim()
            else -> return null
        }
        if (!body.contains("\$VF:")) return null
        val after = body.substringAfter("\$VF:").trim()
        if (!after.startsWith("Could not decompile", ignoreCase = true)) return null
        return after
    }
}
