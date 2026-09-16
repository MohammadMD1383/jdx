package dev.jdx.core.render

/**
 * Machine-legible failure (D-015/D-016, T-010): not-found exits 1 with
 * did-you-mean refs; ambiguity exits 2 with every candidate as a
 * copy-pasteable canonical ref — in text and in JSON alike.
 *
 * Candidates double for both cases in JSON (`candidates[]`): ambiguous overloads
 * or did-you-mean suggestions. Uniform shape, no new keys per case.
 */
public sealed interface ErrorResult {
    /** The query that failed, verbatim. */
    public val query: String

    /** The process exit code: 1 not found, 2 ambiguous (D-015). */
    public val exitCode: Int

    /** Candidates: overloads (ambiguous) or did-you-mean refs (not found). */
    public val candidates: List<String>

    /** Text rendering per PROPOSAL.md §6 (ambiguity) and §16 (did-you-mean). */
    public fun renderText(): String

    /** Error envelope: `ok:false` with `error:{code,message}` plus `candidates[]`. */
    public fun toJson(command: String): String

    /** Valid query, no results (exit 1). */
    public data class NotFound(
        override val query: String,
        public val suggestions: List<String> = emptyList(),
    ) : ErrorResult {
        override val exitCode: Int = 1
        override val candidates: List<String> get() = suggestions

        override fun renderText(): String = buildString {
            append("not found: ").append(query)
            if (suggestions.isNotEmpty()) {
                append("\ndid you mean:")
                for (suggestion in suggestions) append("\n  ").append(suggestion)
            }
        }

        override fun toJson(command: String): String = toErrorJson(command, this)
    }

    /** Under-specified reference (exit 2). Never a guess, always the full list. */
    public data class Ambiguous(
        override val query: String,
        override val candidates: List<String>,
    ) : ErrorResult {
        override val exitCode: Int = 2

        override fun renderText(): String = buildString {
            append("ambiguous: ").append(candidates.size)
                .append(" candidates for ").append(query)
            for (candidate in candidates) append("\n  ").append(candidate)
            append("\nhint: re-run with one of the refs above")
        }

        override fun toJson(command: String): String = toErrorJson(command, this)
    }

    public companion object {
        /** Valid query, no results (exit 1). */
        public fun notFound(query: String, suggestions: List<String> = emptyList()): ErrorResult =
            NotFound(query, suggestions)

        /** Under-specified reference (exit 2). */
        public fun ambiguous(query: String, candidates: List<String>): ErrorResult =
            Ambiguous(query, candidates)
    }
}

private fun toErrorJson(command: String, result: ErrorResult): String {
    val message = if (result is ErrorResult.Ambiguous) {
        "ambiguous: ${result.candidates.size} candidates for ${result.query}"
    } else {
        "not found: ${result.query}"
    }
    val errorJson = "{\"code\":" + result.exitCode + ",\"message\":" + JsonEscape.quote(message) + "}"
    return envelopeJson(
        ok = false,
        command = command,
        query = result.query,
        resultJson = null,
        errorJson = errorJson,
        candidates = result.candidates,
        truncation = null,
        warnings = emptyList(),
        provenance = emptyList(),
    )
}
