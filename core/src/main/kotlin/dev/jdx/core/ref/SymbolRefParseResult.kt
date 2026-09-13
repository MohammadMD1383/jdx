package dev.jdx.core.ref

import dev.jdx.core.model.SymbolRef

/**
 * The result of parsing a symbol reference: either a [SymbolRef] or a positioned,
 * human-readable [Failure]. Parsing never throws — a malformed reference is data an
 * agent can act on (exit code 3 territory), not an exception (CONTRIBUTING.md).
 */
public sealed interface SymbolRefParseResult {
    /** The reference parsed cleanly (possibly under-specified — that is legal). */
    public data class Ok(public val ref: SymbolRef) : SymbolRefParseResult

    /**
     * @param message what is wrong, phrased for an agent to self-correct
     * @param position 0-based index into the original input text
     */
    public data class Failure(public val message: String, public val position: Int) : SymbolRefParseResult
}
