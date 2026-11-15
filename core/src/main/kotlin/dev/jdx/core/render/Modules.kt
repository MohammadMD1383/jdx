package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/** Default cap on module rows; the footer names `--limit` to see more. */
public const val DEFAULT_MODULES_LIMIT: Int = 50

/** One `requires` directive (JPMS). Flags are the printable words, sorted. */
public data class ModuleRequire(
    public val module: String,
    public val flags: List<String> = emptyList(),
) {
    public fun textLine(): String = buildString {
        append("    requires ").append(module)
        for (flag in flags) append(' ').append(flag)
    }

    public fun toJson(): String = buildString {
        append("{\"module\":").append(JsonEscape.quote(module))
        append(",\"flags\":[")
        flags.forEachIndexed { i, flag ->
            if (i > 0) append(',')
            append(JsonEscape.quote(flag))
        }
        append("]}")
    }
}

/**
 * One `exports` or `opens` directive. [to] is empty for an unqualified export/
 * open; otherwise the target module names, sorted.
 */
public data class ModulePackageTarget(
    public val packageName: String,
    public val to: List<String> = emptyList(),
) {
    public fun textLine(kind: String): String = buildString {
        append("    ").append(kind).append(' ').append(packageName)
        if (to.isNotEmpty()) {
            append(" to ").append(to.joinToString(", "))
        }
    }

    public fun toJson(): String = buildString {
        append("{\"package\":").append(JsonEscape.quote(packageName))
        append(",\"to\":[")
        to.forEachIndexed { i, name ->
            if (i > 0) append(',')
            append(JsonEscape.quote(name))
        }
        append("]}")
    }
}

/** One `provides … with …` directive. */
public data class ModuleProvide(
    public val service: String,
    public val providers: List<String>,
) {
    public fun textLine(): String =
        "    provides $service with ${providers.joinToString(", ")}"

    public fun toJson(): String = buildString {
        append("{\"service\":").append(JsonEscape.quote(service))
        append(",\"providers\":[")
        providers.forEachIndexed { i, name ->
            if (i > 0) append(',')
            append(JsonEscape.quote(name))
        }
        append("]}")
    }
}

/**
 * One JPMS module descriptor (issue #25 module-info slice): name, artifact,
 * and the requires/exports/opens/uses/provides directives. Canonical names so
 * the next command is a copy-paste (`jdx show <provider>`, `jdx services …`).
 */
public data class ModuleHit(
    public val name: String,
    public val artifact: String,
    public val open: Boolean = false,
    public val version: String? = null,
    public val requires: List<ModuleRequire> = emptyList(),
    public val exports: List<ModulePackageTarget> = emptyList(),
    public val opens: List<ModulePackageTarget> = emptyList(),
    public val uses: List<String> = emptyList(),
    public val provides: List<ModuleProvide> = emptyList(),
) {
    /** Header line under an artifact group: `  module com.foo open`. */
    public fun headerLine(): String = buildString {
        append("  module ").append(name)
        if (open) append(" open")
        if (version != null) append(" @").append(version)
    }

    /** Directive lines under [headerLine], deterministic order. */
    public fun directiveLines(): List<String> = buildList {
        for (req in requires) add(req.textLine())
        for (exp in exports) add(exp.textLine("exports"))
        for (opn in opens) add(opn.textLine("opens"))
        for (use in uses) add("    uses $use")
        for (prov in provides) add(prov.textLine())
    }

    /** The structural row: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        append("{\"name\":").append(JsonEscape.quote(name))
        append(",\"artifact\":").append(JsonEscape.quote(artifact))
        append(",\"open\":").append(open)
        if (version != null) append(",\"version\":").append(JsonEscape.quote(version))
        append(",\"requires\":[").append(requires.joinToString(",") { it.toJson() }).append(']')
        append(",\"exports\":[").append(exports.joinToString(",") { it.toJson() }).append(']')
        append(",\"opens\":[").append(opens.joinToString(",") { it.toJson() }).append(']')
        append(",\"uses\":[")
        uses.forEachIndexed { i, name ->
            if (i > 0) append(',')
            append(JsonEscape.quote(name))
        }
        append(']')
        append(",\"provides\":[").append(provides.joinToString(",") { it.toJson() }).append(']')
        append('}')
    }
}

/**
 * The full answer to `jdx modules`: module descriptors grouped by artifact.
 * The one result model both renderers read.
 */
public data class ModuleListing(
    public val query: String,
    public val hits: List<ModuleHit>,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /**
     * Text layout: header, artifact group counts, module headers with
     * indented directives, truncation footer. An empty [query] means
     * "list every module" and the header drops the matching clause.
     */
    public fun renderText(color: Boolean = false): String {
        if (hits.isEmpty() && truncation == null) {
            return if (query.isEmpty()) {
                "no modules found"
            } else {
                "no modules matching '$query'"
            }
        }
        val lines = mutableListOf(
            if (query.isEmpty()) "modules" else "modules matching '$query'",
        )
        var currentArtifact: String? = null
        val counts = hits.groupingBy { it.artifact }.eachCount()
        for (hit in hits) {
            if (hit.artifact != currentArtifact) {
                currentArtifact = hit.artifact
                lines.add("${hit.artifact} (${counts.getValue(hit.artifact)})")
            }
            lines.add(hit.headerLine())
            lines.addAll(hit.directiveLines())
        }
        truncation?.let {
            lines.add("${it.shown} of ${it.total} modules shown (${it.hint} to see more)")
        }
        for (warning in warnings) lines.add("warning ${warning.code}: ${warning.message}")
        val plain = lines.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    /** Full JSON envelope; every text row's facts appear here (D-007). */
    public fun toJson(command: String): String {
        val resultJson = "{\"pattern\":" + JsonEscape.quote(query) +
            ",\"modules\":[" + hits.joinToString(",") { it.toJson() } + "]}"
        return envelopeJson(
            ok = true,
            command = command,
            query = query,
            resultJson = resultJson,
            truncation = truncation,
            warnings = warnings,
            provenance = provenance,
        )
    }
}

/**
 * Builds the listing from already-sorted [hits]: keeps the first [limit]
 * modules and reports the rest. Sorted input is the caller's contract
 * (D-007) — artifact, name — and this function never re-sorts.
 */
public fun buildModuleListing(
    query: String,
    hits: List<ModuleHit>,
    limit: Int = DEFAULT_MODULES_LIMIT,
    warnings: List<Warning> = emptyList(),
    provenance: List<Provenance> = emptyList(),
): ModuleListing {
    val (kept, truncation) = truncateEntities(hits, limit) { "--limit $it" }
    return ModuleListing(
        query = query,
        hits = kept,
        truncation = truncation,
        warnings = warnings,
        provenance = provenance,
    )
}
