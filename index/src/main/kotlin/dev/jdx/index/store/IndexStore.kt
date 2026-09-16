package dev.jdx.index.store

import dev.jdx.core.model.ClassInfo
import java.io.Closeable

/**
 * The persistent index (PROPOSAL.md §10, D-003/D-013): one store over many artifacts,
 * every row scoped by an `artifact_id` that is the content hash of the binary jar.
 *
 * Indexing an artifact once serves every workspace that contains it; a workspace is
 * just an ordered list of artifact hashes. Cache invalidation is trivial: a changed
 * file is a different hash.
 *
 * This interface is the seam that keeps SQLite reversible (D-013): **no SQL may appear
 * outside the implementation package** (`dev.jdx.index.store.sqlite`). Callers speak
 * `ClassInfo` and hashes; only the implementation speaks tables.
 *
 * Threading: one writer at a time (SQLite WAL allows many readers alongside it).
 * Implementations must serialise writes internally; concurrent reads are safe.
 */
public interface IndexStore : Closeable {
    /** The schema version this store was opened with ([SqliteSchemaVersion.CURRENT]). */
    public fun schemaVersion(): Int

    /**
     * Records an artifact, or returns the existing row when [hash] is already indexed
     * at the current schema version (the content-hash short-circuit, PROPOSAL.md §10.4).
     * A row whose `schema_ver` is older than [SqliteSchemaVersion.CURRENT] is re-indexed
     * by the caller: this call reports it via [StoredArtifact.needsReindex] instead of
     * silently returning stale rows.
     */
    public fun upsertArtifact(newArtifact: NewArtifact): StoredArtifact

    /** The artifact row for [hash], or `null` when never indexed. */
    public fun findArtifactByHash(hash: String): StoredArtifact?

    /** Every artifact row, ordered by hash (deterministic — D-007). */
    public fun listArtifacts(): List<StoredArtifact>

    /**
     * Drops an artifact and all rows scoped to it (classes, members, edges).
     * Returns `true` when a row existed. This is what `jdx cache gc` (T-018) calls.
     */
    public fun deleteArtifactByHash(hash: String): Boolean

    /**
     * Replaces every class row for [artifactId] with [classes], in one transaction.
     * Batched inserts; per-artifact so a parallel indexer (T-014) can commit one
     * artifact without touching its neighbours.
     */
    public fun replaceClasses(artifactId: Long, classes: List<ClassInfo>)

    /**
     * Every indexed class with this binary name, across all artifacts, ordered by
     * artifact hash. More than one hit is the `DUPLICATE_FQN` case (shading) — the
     * caller reports every provider in [ClassHit.artifact], never picks silently (D-016).
     *
     * The key is the binary name (JVMS §4.2.1, `$`-joined nesting) — the same key the
     * live reader matches on — not the dotted FQN, so nested classes meet exactly.
     */
    public fun findClassesByFqn(binaryName: String): List<ClassHit>

    /** The class [binaryName] as indexed under [artifactId], or `null` when absent. */
    public fun loadClass(artifactId: Long, binaryName: String): ClassInfo?

    /** Every class binary name indexed under [artifactId], sorted. */
    public fun listClassFqns(artifactId: Long): List<String>

    /** How many classes are indexed under [artifactId]. */
    public fun classCount(artifactId: Long): Int
}

/**
 * One indexed class plus the artifact row that provides it — the unit the
 * `DUPLICATE_FQN` warning renders (one line per provider, D-016).
 */
public data class ClassHit(
    public val artifact: StoredArtifact,
    public val clazz: ClassInfo,
)

/**
 * One artifact row. [needsReindex] is `true` when the row predates the current schema
 * (the indexer must re-read the artifact rather than trust its rows).
 */
public data class StoredArtifact(
    public val id: Long,
    public val hash: String,
    public val path: String,
    public val sourcesPath: String?,
    public val kind: String,
    public val jarMtime: Long?,
    public val jarSize: Long?,
    public val indexedAt: Long,
    public val schemaVer: Int,
) {
    public val needsReindex: Boolean get() = schemaVer != SqliteSchemaVersion.CURRENT
}

/**
 * The values a caller supplies when recording an artifact. [hash] is the content id
 * (`ArtifactHash`: SHA-256 truncated to 128 bits); [kind] is an [ArtifactKind] name.
 */
public data class NewArtifact(
    public val hash: String,
    public val path: String,
    public val sourcesPath: String? = null,
    public val kind: String = "BINARY_JAR",
    public val jarMtime: Long? = null,
    public val jarSize: Long? = null,
)

/**
 * Schema versions. `0` means "no schema yet" (a fresh file); [CURRENT] is what a new
 * store writes via `PRAGMA user_version`. Migrations run as a `when` chain in the
 * implementation — add a branch per version, never rewrite history.
 */
public object SqliteSchemaVersion {
    public const val CURRENT: Int = 1
}
