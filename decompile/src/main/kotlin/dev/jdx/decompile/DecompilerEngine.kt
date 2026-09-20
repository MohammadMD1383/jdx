package dev.jdx.decompile

import java.nio.file.Path

/**
 * Which reconstruction engine produced a decompiled text. Vineflower is the
 * default readable-Java engine (T-026); the `javap` opcode engine lands in
 * T-027 and will extend this enum — never a boolean or a string flag.
 */
public enum class DecompilerId(public val flag: String, public val displayName: String) {
    VINEFLOWER("vineflower", "vineflower"),
}

/**
 * Outcome of one single-class decompilation: a value on every
 * agent-reachable path, never a throw. Corrupt bytes, hostile class names,
 * decompiler crashes and timeouts all land in [Failed] with the cause named.
 */
public sealed interface DecompileResult {
    /**
     * The reconstructed source text. [engineVersion] is the engine's own
     * version string (Vineflower `1.12.0`, …) — part of the on-disk cache key,
     * so an engine upgrade never serves stale reconstructions.
     */
    public data class Decompiled(public val text: String, public val engineVersion: String) : DecompileResult

    /** Decompilation did not produce text; [message] names the cause. */
    public data class Failed(public val message: String) : DecompileResult
}

/**
 * Single-class decompilation behind one seam (PROPOSAL.md §11.2, T-026).
 *
 * The caller supplies the class bytes it already read (the service reads them
 * from the winning artifact root) plus the workspace's other binary jars as
 * library context, so generics and inherited members resolve correctly. The
 * engine stages, decompiles, caches and cleans up; the caller only slices the
 * returned text (via the T-021 JavaParser seam) and labels it with the
 * `DECOMPILED_*` provenance.
 *
 * Implementors never throw: every failure is a [DecompileResult.Failed].
 */
public interface DecompilerEngine {
    /** Which engine this is — selects the provenance origin and cache key. */
    public val id: DecompilerId

    /**
     * Decompiles one class. [binaryName] is the `$`-joined binary name (used
     * for staging and cache lookup, never trusted as a path). [classpath] is
     * jar/dir paths for library context; entries that do not exist are
     * ignored. Never throws.
     */
    public fun decompileClass(
        classBytes: ByteArray,
        binaryName: String,
        classpath: List<Path> = emptyList(),
    ): DecompileResult
}
