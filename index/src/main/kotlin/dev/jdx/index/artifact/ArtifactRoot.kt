package dev.jdx.index.artifact

import dev.jdx.core.model.Warning
import java.io.Closeable
import java.io.InputStream
import java.nio.file.Path

/**
 * One place compiled code can be read from: a jar, a class directory, or the JDK itself.
 *
 * The three shapes are read uniformly through this interface (T-007): the indexer and every
 * later reader list [classEntryPaths] and pull bytes with [openClass], never caring which
 * shape sits underneath. Paths are normalised `com/foo/Bar.class` slash paths, sorted, with
 * multi-release variants already resolved to the bytes the running JVM would load.
 *
 * Reading parses bytes with ASM (T-008) — it never loads the class into this JVM (D-017).
 */
public interface ArtifactRoot : Closeable {

    /** Human label for messages and provenance: a file name, a directory, or `jrt:/`. */
    public val displayName: String

    /** Which of the three shapes this root is. */
    public val kind: ArtifactKind

    /**
     * Warnings discovered while opening: `MULTI_RELEASE_VARIANT` when a versioned class
     * won, `DUPLICATE_FQN` when two JDK modules ship the same class. Sorted by code.
     */
    public val warnings: List<Warning>

    /**
     * Every servable class entry, normalised slash paths, sorted, `module-info.class`
     * excluded. Deterministic for identical inputs (CLAUDE.md §2.5).
     */
    public fun classEntryPaths(): List<String>

    /**
     * Opens one class entry for reading. The caller closes the stream.
     *
     * @throws ArtifactReadException when [path] is unsafe, absent, or breaches a
     *   decompression cap (D-015 exit 5 at the service layer).
     */
    public fun openClass(path: String): InputStream

    /**
     * Content-hash identity of this root: the jar's bytes, the directory's content hash,
     * or the JDK release for `jrt:/`. The index cache key (PROPOSAL.md §10.2).
     */
    public fun stableId(): String

    /**
     * This root as a library path for the decompiler's type context (T-026):
     * the jar file, the class directory, or `null` when the root has no
     * filesystem path (`jrt:/` — the JDK ships no jar to point at).
     */
    public val libraryPath: Path?
        get() = null
}
