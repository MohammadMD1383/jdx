package dev.jdx.core.model

/**
 * Where a piece of output came from — the confidence signal every answer carries (§3.5).
 * Ordered by trust: sources beat bytecode beats decompilation.
 */
public enum class Origin {
    /** Read directly from a class file via ASM — structural truth (D-009). */
    BYTECODE,

    /** Read from a `.java`/`.kt` source file — bodies, param names, docs. */
    SOURCES,

    /** Reconstructed by the bundled Vineflower engine. */
    DECOMPILED_VINEFLOWER,

    /** Reconstructed by the `javap` engine. */
    DECOMPILED_JAVAP,

    /** Read from the running JDK via `jrt:/` (paired with `src.zip` when present). */
    JRT,
}

/**
 * The provenance of one answer or entity: which artifact it came from, through which
 * origin, and where in it (file and line range, when known — source-derived only).
 * Produced by every loader in `index`/`sources`/`decompile`.
 */
public data class Provenance(
    public val artifact: String,
    public val origin: Origin,
    public val file: String? = null,
    public val lineRange: IntRange? = null,
)
