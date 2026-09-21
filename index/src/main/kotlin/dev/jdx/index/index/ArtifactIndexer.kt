package dev.jdx.index.index

import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.ReferenceEdge
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.artifact.ArtifactReadException
import dev.jdx.index.artifact.ArtifactRoot
import dev.jdx.index.artifact.JarArtifact
import dev.jdx.index.artifact.JrtArtifact
import dev.jdx.index.artifact.SourcesPair
import dev.jdx.index.asm.AsmClassReader
import dev.jdx.index.asm.ClassReadResult
import dev.jdx.index.refs.ReferenceExtractor
import dev.jdx.index.store.IndexStore
import dev.jdx.index.store.NewArtifact
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

/**
 * Thrown when a whole artifact cannot be indexed (unopenable jar, unreadable
 * directory, index write failure). Single-class failures never throw — they
 * become [WarningCode.CORRUPT_CLASS], [WarningCode.UNSUPPORTED_CLASS_VERSION] or
 * [WarningCode.UNNAMEABLE_CLASS] warnings on the result, so one bad entry never aborts
 * its artifact (D-017).
 */
public class IndexException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** Whether one artifact was (re-)indexed, skipped, or failed. */
public enum class EntryStatus {
    /** The artifact was read and its rows replaced in one transaction. */
    INDEXED,

    /** The content hash was already indexed at the current schema — untouched. */
    SKIPPED,

    /** The artifact could not be opened, hashed, or stored; see [ArtifactIndexResult.error]. */
    FAILED,
}

/**
 * What indexing one artifact did. Produced by [ArtifactIndexer]; informational
 * timing ([elapsedMs], [classesPerSecond]) is the only non-deterministic part —
 * hashes, counts, warnings and stored rows are stable across runs (D-007).
 */
public data class ArtifactIndexResult(
    /** The artifact as passed in: an absolute path, or `jrt:/` for the JDK. */
    public val path: String,
    /** The content-hash cache key, or `null` when hashing itself failed. */
    public val hash: String?,
    /** The store row id, or `null` when nothing was stored ([EntryStatus.FAILED]). */
    public val artifactId: Long?,
    public val status: EntryStatus,
    /** Classes read (INDEXED) or already stored (SKIPPED); 0 when FAILED. */
    public val classCount: Int,
    /** Root warnings plus one per skipped class, sorted by code. */
    public val warnings: List<Warning>,
    public val elapsedMs: Long,
    /** Non-null only for [EntryStatus.FAILED]: why the artifact could not be indexed. */
    public val error: String?,
) {
    /** Indexed classes per second; 0 when nothing was read (SKIPPED/FAILED/empty). */
    public val classesPerSecond: Double
        get() = if (status == EntryStatus.INDEXED && elapsedMs > 0 && classCount > 0) {
            classCount * 1000.0 / elapsedMs
        } else {
            0.0
        }
}

/**
 * What one [ArtifactIndexer.indexMany] run did. [results] are sorted by path,
 * so the report is deterministic even though artifacts finish in whatever order
 * the virtual threads manage (D-007).
 */
public data class IndexReport(
    public val results: List<ArtifactIndexResult>,
    public val durationMs: Long,
) {
    public val indexedCount: Int get() = results.count { it.status == EntryStatus.INDEXED }
    public val skippedCount: Int get() = results.count { it.status == EntryStatus.SKIPPED }
    public val failedCount: Int get() = results.count { it.status == EntryStatus.FAILED }
    public val totalClasses: Int get() = results.sumOf { it.classCount }
    public val failures: List<ArtifactIndexResult> get() = results.filter { it.status == EntryStatus.FAILED }
}

/**
 * Progress callback for long runs. Called once per finished artifact, in
 * completion order (which varies run to run — assert on [IndexReport.results],
 * never on callback order). Called on the thread running `indexMany`, so the
 * implementation needs no synchronisation of its own.
 */
public fun interface IndexProgressListener {
    public fun onArtifactFinished(result: ArtifactIndexResult)
}

/**
 * The bytecode indexing pipeline (T-014, PROPOSAL.md §10.4).
 *
 * Per artifact: hash → short-circuit on a current-schema hit → ASM-read every
 * class entry (bad entries become warnings) → `upsert` + one-transaction
 * `replaceClasses` + `replaceReferences` (edges extracted from the same
 * bytes). Across artifacts: virtual-thread fan-out, one task each.
 * Sources are deliberately untouched here — they index lazily on first source
 * query (§10.4 step 4, M3's job).
 *
 * Threading: concurrent calls share one [IndexStore], which serialises writes
 * internally (see its KDoc); reads never block each other (SQLite WAL, D-013).
 * No SQL here — every store touch goes through the [IndexStore] interface.
 */
public object ArtifactIndexer {

    /**
     * Indexes the artifact at [path] (jar, class dir, or zip): opens it,
     * derives its store metadata from the path, and delegates to [indexRoot].
     *
     * @throws IndexException when the artifact cannot be opened, hashed, or stored.
     */
    public fun indexOne(
        store: IndexStore,
        path: Path,
        explicitSources: Path? = null,
        listener: IndexProgressListener? = null,
    ): ArtifactIndexResult {
        val absolute = path.toAbsolutePath().normalize()
        val label = absolute.toString()
        val root = try {
            ArtifactLoader.open(absolute, explicitSources)
        } catch (e: ArtifactReadException) {
            throw IndexException(e.message ?: "cannot open artifact $label", e)
        } catch (e: Exception) {
            throw IndexException("cannot open artifact $label: ${e.message}", e)
        }
        root.use {
            val (mtime, size) = fileMetadata(absolute)
            return indexRoot(
                store = store,
                root = root,
                pathLabel = label,
                kindName = root.kind.name,
                sourcesPath = sourcesPathOf(root),
                jarMtime = mtime,
                jarSize = size,
                listener = listener,
            )
        }
    }

    /**
     * Indexes the running JDK (`jrt:/`, PROPOSAL.md §5.1 `JrtModules`) under the
     * `jrt:/` label. The content key is the runtime version, so upgrading the
     * JDK re-indexes rather than trusting stale rows.
     *
     * @throws IndexException when the JDK cannot be read or stored.
     */
    public fun indexJdk(
        store: IndexStore,
        listener: IndexProgressListener? = null,
    ): ArtifactIndexResult {
        val root = try {
            ArtifactLoader.openJdk()
        } catch (e: ArtifactReadException) {
            throw IndexException(e.message ?: "cannot open the running JDK", e)
        } catch (e: Exception) {
            throw IndexException("cannot open the running JDK: ${e.message}", e)
        }
        root.use {
            return indexRoot(
                store = store,
                root = root,
                pathLabel = "jrt:/",
                kindName = root.kind.name,
                sourcesPath = sourcesPathOf(root),
                jarMtime = null,
                jarSize = null,
                listener = listener,
            )
        }
    }

    /**
     * Indexes many artifacts in parallel — one virtual thread per path — and
     * returns the sorted [IndexReport]. A path that cannot be indexed becomes a
     * [EntryStatus.FAILED] entry; its neighbours are unaffected.
     *
     * Duplicate and relative paths are normalised (absolute, distinct, sorted)
     * before fanning out, so the report is stable across runs (D-007).
     */
    public fun indexMany(
        store: IndexStore,
        paths: List<Path>,
        listener: IndexProgressListener? = null,
    ): IndexReport {
        val startNs = System.nanoTime()
        val ordered = paths.map { it.toAbsolutePath().normalize() }.distinct().sorted()
        if (ordered.isEmpty()) return IndexReport(emptyList(), 0)
        val collected = ArrayList<ArtifactIndexResult>(ordered.size)
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val completion = ExecutorCompletionService<ArtifactIndexResult>(pool)
            for (path in ordered) {
                completion.submit(
                    Callable {
                        try {
                            indexOne(store, path)
                        } catch (e: IndexException) {
                            failedResult(path.toString(), e.message ?: "indexing failed")
                        } catch (e: Exception) {
                            failedResult(
                                path.toString(),
                                "${e.javaClass.simpleName}: ${e.message ?: "no detail"}",
                            )
                        }
                    },
                )
            }
            // Completion order: the listener hears about each artifact the moment
            // it lands, which is what keeps long runs visibly alive.
            repeat(ordered.size) {
                collected.add(completion.take().get())
                listener?.onArtifactFinished(collected.last())
            }
        }
        collected.sortBy { it.path }
        return IndexReport(collected, (System.nanoTime() - startNs) / 1_000_000)
    }

    /**
     * Indexes one already-open [ArtifactRoot] into [store]. This is the seam the
     * two `indexOne`/`indexJdk` entry points share, and the one tests use with
     * crafted roots: metadata the loader would derive from a path ([pathLabel],
     * [kindName], [sourcesPath], [jarMtime], [jarSize]) is passed explicitly.
     *
     * Short-circuit (§10.4 step 2): a hash already stored at the current schema
     * returns [EntryStatus.SKIPPED] without reading or writing anything — the
     * stored rows are the answer. Anything else (missing hash, stale schema)
     * re-reads the artifact and replaces its rows in one transaction.
     *
     * @throws IndexException when hashing, listing, or storing fails wholesale.
     */
    public fun indexRoot(
        store: IndexStore,
        root: ArtifactRoot,
        pathLabel: String,
        kindName: String,
        sourcesPath: String?,
        jarMtime: Long?,
        jarSize: Long?,
        listener: IndexProgressListener? = null,
    ): ArtifactIndexResult {
        val startNs = System.nanoTime()
        fun elapsedMs(): Long = (System.nanoTime() - startNs) / 1_000_000
        val hash = try {
            root.stableId()
        } catch (e: Exception) {
            throw IndexException("cannot hash artifact $pathLabel: ${e.message}", e)
        }
        val existing = try {
            store.findArtifactByHash(hash)
        } catch (e: Exception) {
            throw IndexException("cannot query the index for $pathLabel: ${e.message}", e)
        }
        if (existing != null && !existing.needsReindex) {
            val count = try {
                store.classCount(existing.id)
            } catch (e: Exception) {
                throw IndexException("cannot query the index for $pathLabel: ${e.message}", e)
            }
            val skipped = ArtifactIndexResult(
                path = pathLabel,
                hash = hash,
                artifactId = existing.id,
                status = EntryStatus.SKIPPED,
                classCount = count,
                warnings = emptyList(),
                elapsedMs = elapsedMs(),
                error = null,
            )
            listener?.onArtifactFinished(skipped)
            return skipped
        }
        val entries = try {
            root.classEntryPaths()
        } catch (e: Exception) {
            throw IndexException("cannot list classes in $pathLabel: ${e.message}", e)
        }
        // Entry order is the artifact's sorted order — insertion order into the
        // store is therefore deterministic across runs and processes (D-007).
        val classes = ArrayList<ClassInfo>(entries.size)
        val edges = ArrayList<ReferenceEdge>()
        val warnings = ArrayList<Warning>()
        warnings.addAll(root.warnings)
        for (entry in entries) {
            val binary = entry.removeSuffix(".class").replace('/', '.')
            val bytes = try {
                root.openClass(entry).use { it.readBytes() }
            } catch (e: Exception) {
                warnings.add(
                    Warning(
                        code = WarningCode.CORRUPT_CLASS,
                        message = "cannot read $binary from $pathLabel: ${e.message}",
                        subject = binary,
                    ),
                )
                continue
            }
            // AsmClassReader never throws: every failure mode arrives as a
            // warning-carrying value, so one bad class cannot abort its artifact.
            // Reference extraction runs on the same bytes for readable classes;
            // it never throws either, so it adds no failure mode here.
            when (val read = AsmClassReader.read(bytes, "$binary in $pathLabel")) {
                is ClassReadResult.Ok -> {
                    classes.add(read.info)
                    edges.addAll(ReferenceExtractor.extract(bytes))
                }
                is ClassReadResult.UnsupportedVersion ->
                    warnings.add(read.warning.copy(subject = binary))
                is ClassReadResult.Corrupt ->
                    warnings.add(read.warning.copy(subject = binary))
            }
        }
        var classTotal = 0
        val stored = try {
            val upserted = store.upsertArtifact(
                NewArtifact(
                    hash = hash,
                    path = pathLabel,
                    sourcesPath = sourcesPath,
                    kind = kindName,
                    jarMtime = jarMtime,
                    jarSize = jarSize,
                ),
            )
            // One transaction per artifact (batched inside the store): a parallel
            // indexer commits whole artifacts without touching their neighbours.
            // Classes first: replaceClasses clears the artifact's stored edges
            // (scoped delete), then the fresh edges land beside them.
            // Both lists are released right after storing: on JDK-scale
            // artifacts they hold ~10^5 objects each, and keeping them alive
            // next to the store's own member-id map OOMs small heaps (T-029).
            classTotal = classes.size
            store.replaceClasses(upserted.id, classes)
            classes.clear()
            store.replaceReferences(upserted.id, edges)
            edges.clear()
            upserted
        } catch (e: Exception) {
            throw IndexException("cannot store the index for $pathLabel: ${e.message}", e)
        }
        val indexed = ArtifactIndexResult(
            path = pathLabel,
            hash = hash,
            artifactId = stored.id,
            status = EntryStatus.INDEXED,
            classCount = classTotal,
            warnings = warnings.sortedBy { it.code },
            elapsedMs = elapsedMs(),
            error = null,
        )
        listener?.onArtifactFinished(indexed)
        return indexed
    }

    // -- metadata helpers ------------------------------------------------------

    private fun failedResult(path: String, message: String): ArtifactIndexResult =
        ArtifactIndexResult(
            path = path,
            hash = null,
            artifactId = null,
            status = EntryStatus.FAILED,
            classCount = 0,
            warnings = emptyList(),
            elapsedMs = 0,
            error = message,
        )

    /** File mtime + size for jars (index metadata); `null`s for anything else. */
    private fun fileMetadata(absolute: Path): Pair<Long?, Long?> = try {
        if (Files.isRegularFile(absolute)) {
            Pair(Files.getLastModifiedTime(absolute).toMillis(), Files.size(absolute))
        } else {
            Pair(null, null)
        }
    } catch (_: Exception) {
        Pair(null, null)
    }

    /** The sources path the store records: the paired `-sources.jar`, if any. */
    private fun sourcesPathOf(root: ArtifactRoot): String? = when (root) {
        is JarArtifact -> when (val pair = root.sourcesPair) {
            is SourcesPair.External -> pair.path.toString()
            else -> null
        }
        is JrtArtifact -> root.jdkSources?.toString()
        else -> null
    }
}
