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
     * excluded. Deterministic for identical inputs (AGENTS.md).
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
     * Every base `META-INF/services/<iface>` entry in this root (issue #25),
     * normalised slash paths, sorted. Versioned copies under
     * `META-INF/versions/` are excluded in this slice. Empty for roots that
     * carry no classic ServiceLoader files (`jrt:/` today).
     */
    public fun serviceEntryPaths(): List<String> = emptyList()

    /**
     * Opens one resource entry (today: a [serviceEntryPaths] path) for reading.
     * The caller closes the stream. Default refuses — roots that list services
     * override this.
     *
     * @throws ArtifactReadException when [path] is unsafe, absent, or breaches a
     *   decompression cap (D-015 exit 5 at the service layer).
     */
    public fun openResource(path: String): InputStream =
        throw ArtifactReadException("artifact read error: $displayName has no resource $path")

    /**
     * Every `module-info.class` entry in this root (issue #25 module-info slice),
     * normalised slash paths, sorted. For jars/class-dirs this is typically
     * `["module-info.class"]` or empty; for `jrt:/` one path per JDK module
     * (`java.base/module-info.class`, …). Versioned copies under
     * `META-INF/versions/` follow multi-release selection for jars.
     */
    public fun moduleInfoPaths(): List<String> = emptyList()

    /**
     * Opens one [moduleInfoPaths] entry for reading. The caller closes the
     * stream. Default refuses — roots that list module-info override this.
     *
     * @throws ArtifactReadException when [path] is unsafe, absent, or breaches a
     *   decompression cap (D-015 exit 5 at the service layer).
     */
    public fun openModuleInfo(path: String): InputStream =
        throw ArtifactReadException("artifact read error: $displayName has no module-info $path")

    /**
     * Every base `META-INF/MANIFEST.MF` entry in this root (issue #25 manifest
     * slice), normalised slash paths, sorted. Typically `[META-INF/MANIFEST.MF]`
     * or empty; `jrt:/` is empty (JDK modules have no classic jar manifest).
     * Versioned copies under `META-INF/versions/` are excluded.
     */
    public fun manifestPaths(): List<String> = emptyList()

    /**
     * Opens one [manifestPaths] entry for reading. The caller closes the
     * stream. Default refuses — roots that list a manifest override this.
     *
     * @throws ArtifactReadException when [path] is unsafe, absent, or breaches a
     *   decompression cap (D-015 exit 5 at the service layer).
     */
    public fun openManifest(path: String): InputStream =
        throw ArtifactReadException("artifact read error: $displayName has no manifest $path")

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
