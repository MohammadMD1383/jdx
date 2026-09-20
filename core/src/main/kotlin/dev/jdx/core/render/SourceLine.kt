package dev.jdx.core.render

import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance

/**
 * The `source:` line for `body`/`source` answers (T-022/T-023/T-026): which
 * artifact the lines were sliced from, through which origin, and where.
 *
 * Decompiled origins are always labelled as reconstructions (CONTRIBUTING.md:
 * never present a reconstruction as ground truth): the binary jar alone must
 * never read as a sources answer. The wording matches the README contract
 * (`decompiled by vineflower from … ⚠ reconstructed`).
 */
public fun renderSourceLine(
    provenance: Provenance?,
    file: String,
    startLine: Int,
    endLine: Int,
): String {
    val artifact = provenance?.artifact ?: "?"
    val range = "$file:$startLine-$endLine"
    return when (provenance?.origin) {
        Origin.DECOMPILED_VINEFLOWER ->
            "source: decompiled by vineflower from $artifact · $range  ⚠ reconstructed"
        Origin.DECOMPILED_JAVAP ->
            "source: disassembled by javap from $artifact · $range  ⚠ reconstructed"
        else -> "source: $artifact · $range"
    }
}
