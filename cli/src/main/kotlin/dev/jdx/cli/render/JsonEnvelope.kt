package dev.jdx.cli.render

import dev.jdx.cli.service.DoctorReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The `--json` envelope (PROPOSAL.md §8.2), M0 seed.
 *
 * Two renderers over one result model (D-007): text stays human-first, JSON carries the same
 * information inside this envelope. T-010 promotes this into the shared renderers and adds
 * the remaining envelope fields (`query`, `truncated`, `warnings`, `provenance`) plus the
 * checked-in schema — until then every command builds its envelope here, so the shape lives
 * in exactly one place.
 */
@Serializable
data class Envelope(
    val jdx: Int = ENVELOPE_VERSION,
    val ok: Boolean,
    val command: String,
    val result: JsonElement,
) {
    companion object {
        /** Envelope version. Bump only as a breaking change (PROPOSAL.md §16 risks). */
        const val ENVELOPE_VERSION: Int = 1
    }
}

/**
 * The single JSON configuration for all CLI output: compact, order-stable, no nulls — and
 * defaults always encoded. That last one is load-bearing: the `"jdx": 1` envelope marker is a
 * default-valued property, and kotlinx.serialization drops defaults unless `encodeDefaults`
 * is set (L-014).
 */
val JdxJson: Json = Json {
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Encodes one command result inside the envelope. `ok` mirrors the process exit code —
 * `true` iff the command exits 0 — so agents can branch on either signal identically.
 */
fun envelopeJson(command: String, ok: Boolean, result: JsonElement): String =
    JdxJson.encodeToString(Envelope.serializer(), Envelope(ok = ok, command = command, result = result))

@Serializable
data class VersionResult(val version: String)

/** `jdx version --json`. */
fun VersionResult.toJson(): String =
    envelopeJson(command = "version", ok = true, result = JdxJson.encodeToJsonElement(this))

/** `jdx version` text: the same string `--version` prints. */
fun VersionResult.renderText(): String = "jdx version $version"

/** `jdx doctor --json`. `ok` is false iff any check failed (mirrors exit code 6). */
fun DoctorReport.toJson(): String =
    envelopeJson(command = "doctor", ok = !hasFailures, result = JdxJson.encodeToJsonElement(this))

/** `jdx doctor` text: one header plus one line per check, no tables (PROPOSAL.md §8.1). */
fun DoctorReport.renderText(): String = buildString {
    appendLine("jdx doctor")
    for (check in checks) {
        appendLine("  ${check.name}: ${check.status.name.lowercase()} (${check.detail})")
    }
}.trimEnd()
