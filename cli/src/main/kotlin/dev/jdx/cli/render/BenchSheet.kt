package dev.jdx.cli.render

import dev.jdx.cli.bench.BenchRunner
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

/**
 * `jdx bench` rendering (T-050, PROPOSAL.md §15).
 *
 * One result model ([BenchResult]) feeds both renderers (D-007): text is the
 * human timing table, JSON the same rows in the standard envelope. Timings
 * are inherently non-deterministic (the disciplined AGENTS.md exception);
 * row order, labels and targets are byte-stable.
 */
@Serializable
public data class BenchRowJson(
    val name: String,
    val targetMs: Long,
    val medianMs: Long,
    val withinTarget: Boolean,
    /** Set only on the opt-in usages row; omitted from JSON when null. */
    val note: String? = null,
)

@Serializable
public data class BenchResult(
    val label: String,
    val classCount: Int,
    val iterations: Int,
    val rows: List<BenchRowJson>,
)

/** Builds the [BenchResult] for a finished [BenchRunner.Report]. */
public fun benchResult(report: BenchRunner.Report): BenchResult =
    BenchResult(
        report.label,
        report.classCount,
        report.iterations,
        report.rows.map { BenchRowJson(it.name, it.targetMs, it.medianMs, it.withinTarget, it.note) },
    )

/** `jdx bench --json`. */
public fun BenchResult.toJson(): String =
    envelopeJson(command = "bench", ok = true, result = JdxJson.encodeToJsonElement(this))

/**
 * `jdx bench` text: header, one padded row per case, then any row notes, then
 * the within-target tally. A row with a null note adds nothing, so the default
 * table (no usages row) stays byte-identical.
 */
public fun BenchResult.renderText(): String {
    val width = rows.maxOfOrNull { it.name.length } ?: 0
    val within = rows.count { it.withinTarget }
    return buildString {
        appendLine("jdx bench $label ($classCount classes, $iterations iterations)")
        for (row in rows) {
            val status = if (row.withinTarget) "ok" else "OVER"
            append("  ").append(row.name.padEnd(width)).append("  ")
                .append(row.medianMs.toString().padStart(6)).append(" ms")
                .append("  (target ≤ ").append(row.targetMs.toString()).append(" ms)  ")
                .appendLine(status)
        }
        for (note in rows.mapNotNull { it.note }.distinct()) {
            append("note: ").appendLine(note)
        }
        append("$within/${rows.size} within target")
    }
}
