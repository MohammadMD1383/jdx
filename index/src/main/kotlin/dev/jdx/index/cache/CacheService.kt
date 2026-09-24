package dev.jdx.index.cache

import dev.jdx.index.service.JdxService
import dev.jdx.index.store.IndexStore
import dev.jdx.index.store.StoredArtifact
import dev.jdx.index.store.sqlite.SqliteIndexStore
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceCorruptException
import dev.jdx.index.workspace.WorkspaceStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * The behaviour behind `jdx cache info|gc|clear` (T-018, PROPOSAL.md §7.4 and §10.2).
 *
 * The cache root (`~/.cache/jdx`, D-013) holds the index database
 * (`index/v1.db*`) and the derived project cache (`auto/`, T-016) — everything
 * in it is regenerable, which is what makes `clear` safe. This service owns the
 * policy (what counts as referenced, what `clear` removes); the CLI adapter
 * only parses flags, renders, and maps [CacheResult.Failure.exitCode] to the
 * process exit (D-004).
 *
 * Reference rule for `gc`: an artifact is *referenced* when its normalised
 * absolute path is produced by expanding some workspace's jar specs. Specs that
 * do not resolve (missing files, empty globs) are skipped — the artifact they
 * would have named is stale by definition and collected. `JRT` rows have no
 * filesystem path, so they are kept while any workspace includes the JDK, or
 * when no workspaces exist at all (the default query includes the JDK).
 *
 * Deliberate v1 approximation (PROPOSAL.md §10.2 says "no workspace references
 * *and* none was used recently"): the store records `indexed_at` at creation
 * time, not last use, so there is no recency signal to consult. `gc` collects
 * by reference only; a `--older-than` recency flag can land once the store
 * tracks last use.
 *
 * All results are deterministic (D-007): artifact iteration follows the
 * store's hash order, deletions are reported sorted by path, and no
 * timestamps, hashes or absolute paths leak into the *counts* — paths do name
 * deleted artifacts (as `doctor` names its cache root), while content hashes
 * stay in the JSON payload only.
 */
public class CacheService(
    /** The cache root (production: `~/.cache/jdx`). */
    public val cacheRoot: Path,
    /** Named workspaces consulted by `gc`; injectable so tests avoid the real home. */
    public val workspaceStore: WorkspaceStore = FileWorkspaceStore.system(),
    /** Opens the index database; injectable so tier-1 tests never touch SQLite. */
    public val openStore: (Path) -> IndexStore = SqliteIndexStore::open,
    /**
     * Expands one workspace jar spec to absolute paths. Defaults to the same
     * expansion queries use ([JdxService.expandJarSpec]); tests inject a fake
     * so reference policy is provable without a filesystem.
     */
    public val expandJarSpec: (String) -> List<Path> = JdxService::expandJarSpec,
    /**
     * Whether the index database exists. Defaults to a filesystem probe; tier-1
     * tests inject ` { true }` with a fake [openStore] so the reference policy
     * is provable without SQLite or disk.
     */
    public val isDbPresent: () -> Boolean = { Files.isRegularFile(cacheRoot.resolve("index/v1.db")) },
    /**
     * Daemon socket directory (`$XDG_RUNTIME_DIR/jdx`, T-041) whose orphan
     * `.log` files `gc` sweeps (T-085). `null` (the default) skips the sweep —
     * today's behaviour; production wires the real directory in the CLI
     * adapter, which already depends on `:server`.
     */
    public val daemonRuntimeDir: Path? = null,
    /**
     * Whether a daemon answers at a socket path. Defaults to `false` (every
     * log reads as orphaned); production passes the `health` handshake.
     * Must never throw — a throwing probe keeps the log (T-085).
     */
    public val daemonSocketAlive: (Path) -> Boolean = { false },
) {
    /** The shared index database file (D-013). */
    public val dbFile: Path = cacheRoot.resolve("index/v1.db")

    /** Derived project workspaces (T-016); wiped by `clear`, re-derived on demand. */
    public val autoDir: Path = cacheRoot.resolve("auto")

    public companion object {
        /**
         * The production service: [cacheRoot] defaults to `~/.cache/jdx` off
         * this process's home (mirroring `doctor`'s literal roots, D-027).
         */
        public fun system(cacheRoot: Path? = null): CacheService {
            val root = cacheRoot ?: Path.of(System.getProperty("user.home"), ".cache", "jdx")
            return CacheService(root)
        }
    }

    /** `cache info`: sizes and counts; a missing database is an empty report, not an error. */
    public fun info(): CacheResult<CacheInfo> {
        val cacheBytes = directoryBytes(cacheRoot)
        if (!isDbPresent()) {
            return CacheResult.Ok(
                CacheInfo(
                    dbPath = dbFile.toString(),
                    dbExists = false,
                    dbBytes = 0,
                    schemaVersion = null,
                    artifacts = 0,
                    classes = 0,
                    cacheBytes = cacheBytes,
                ),
            )
        }
        val dbBytes = fileBytes(dbFile)
        return opened { store ->
            val artifacts = store.listArtifacts()
            var classes = 0
            for (artifact in artifacts) classes += store.classCount(artifact.id)
            CacheInfo(
                dbPath = dbFile.toString(),
                dbExists = true,
                dbBytes = dbBytes,
                schemaVersion = store.schemaVersion(),
                artifacts = artifacts.size,
                classes = classes,
                cacheBytes = cacheBytes,
            )
        }
    }

    /**
     * `cache gc`: deletes stale artifacts (stored file gone) and artifacts no
     * workspace references, plus orphan daemon `.log` files (T-085). With
     * [dryRun] nothing is deleted and the report names what would be.
     */
    public fun gc(dryRun: Boolean = false): CacheResult<GcReport> {
        if (!isDbPresent()) {
            return CacheResult.Ok(
                GcReport(emptyList(), kept = 0, dryRun = dryRun, daemonLogs = sweepDaemonLogs(dryRun)),
            )
        }
        // References resolve before the store opens: a corrupt workspace (exit 4)
        // must fail without touching the database.
        val references = when (val collected = collectReferences()) {
            is CacheResult.Ok -> collected.value
            is CacheResult.Failure -> return collected
        }
        return opened { store ->
            val artifacts = store.listArtifacts()
            val deleted = mutableListOf<GcDeleted>()
            var kept = 0
            for (artifact in artifacts) {
                if (isReferenced(artifact, references)) {
                    kept++
                    continue
                }
                val classes = store.classCount(artifact.id)
                if (!dryRun) store.deleteArtifactByHash(artifact.hash)
                deleted.add(GcDeleted(path = artifact.path, hash = artifact.hash, classes = classes))
            }
            GcReport(
                deleted = deleted.sortedBy { it.path },
                kept = kept,
                dryRun = dryRun,
                daemonLogs = sweepDaemonLogs(dryRun),
            )
        }
    }

    /**
     * `cache clear`: deletes the index database files (`v1.db*`) and the
     * derived `auto/` cache. An absent cache is success with nothing deleted.
     */
    public fun clear(): CacheResult<ClearReport> {
        return try {
            clearFiles()
        } catch (e: Exception) {
            CacheResult.Failure("cannot clear the cache: ${e.message}", exitCode = 6)
        }
    }

    private fun clearFiles(): CacheResult<ClearReport> {
        val dbTargets = listOf(dbFile) + walCompanions(dbFile)
        var files = 0
        var bytes = 0L
        val failures = mutableListOf<String>()
        for (target in dbTargets) {
            if (!target.isRegularFile()) continue
            bytes += fileBytes(target)
            if (deleteQuietly(target)) files++ else failures.add(target.toString())
        }
        if (autoDir.isDirectory()) {
            val autoFiles = listFilesRecursively(autoDir)
            for (file in autoFiles) {
                bytes += fileBytes(file)
                if (deleteQuietly(file)) files++ else failures.add(file.toString())
            }
            // Remove now-empty directories bottom-up; a leftover empty dir is harmless.
            listDirsBottomUp(autoDir).forEach { deleteQuietly(it) }
        }
        if (failures.isNotEmpty()) {
            return CacheResult.Failure(
                "cannot clear the cache: ${failures.size} file(s) could not be deleted " +
                    "(first: ${failures.first()})",
                exitCode = 6,
            )
        }
        return CacheResult.Ok(
            ClearReport(filesDeleted = files, bytesFreed = bytes, dbWasPresent = files > 0),
        )
    }

    // -- internals ----------------------------------------------------------

    /** Referenced paths plus whether any JRT row must be kept. */
    private data class References(val paths: Set<String>, val keepJrt: Boolean)

    private fun collectReferences(): CacheResult<References> {
        val names = try {
            workspaceStore.listNames()
        } catch (e: IOException) {
            return CacheResult.Failure("cannot list workspaces: ${e.message}", exitCode = 6)
        }
        val paths = mutableSetOf<String>()
        // No workspaces at all: the default query includes the JDK, so JRT stays.
        var keepJrt = names.isEmpty()
        for (name in names) {
            val definition = try {
                workspaceStore.load(name)
            } catch (e: WorkspaceCorruptException) {
                return CacheResult.Failure(
                    e.message ?: "workspace '$name' is corrupt",
                    exitCode = 4,
                )
            } catch (e: IOException) {
                return CacheResult.Failure("cannot read workspace '$name': ${e.message}", exitCode = 6)
            } ?: continue
            if (definition.includeJdk) keepJrt = true
            for (spec in definition.jars) {
                val expanded = try {
                    expandJarSpec(spec)
                } catch (e: Exception) {
                    // Unresolvable specs name nothing; their artifacts are stale by definition.
                    continue
                }
                for (path in expanded) paths.add(normalise(path))
            }
        }
        return CacheResult.Ok(References(paths, keepJrt))
    }

    private fun isReferenced(artifact: StoredArtifact, references: References): Boolean {
        if (artifact.kind == JRT_KIND) return references.keepJrt
        val normalised = try {
            normalise(Path.of(artifact.path))
        } catch (e: Exception) {
            return false
        }
        return normalised in references.paths
    }

    /** Opens the store, runs [block], maps store failures to exit 6 — never throws. */
    private fun <T> opened(block: (IndexStore) -> T): CacheResult<T> {
        val store = try {
            openStore(dbFile)
        } catch (e: Exception) {
            return CacheResult.Failure("cannot open the index database ($dbFile): ${e.message}", exitCode = 6)
        }
        return try {
            CacheResult.Ok(block(store))
        } catch (e: Exception) {
            CacheResult.Failure("index database read failed: ${e.message}", exitCode = 6)
        } finally {
            runCatching { store.close() }
        }
    }

    /**
     * Orphan daemon `.log` sweep (T-085, D-066): the `$XDG_RUNTIME_DIR/jdx`
     * directory collects one `.log` sibling per spawned daemon, and neither
     * idle shutdown nor `daemon stop` removes them. `gc` owns them now: a log
     * is orphaned when its sibling `.sock` is absent or no daemon answers
     * there; logs of live daemons are kept as running evidence.
     *
     * Only top-level `*.log` regular files are considered. Never throws: a
     * missing directory sweeps nothing, unreadable state keeps the file, and
     * a throwing probe reads as "cannot prove orphaned" (keep). Names are
     * sorted file names, never paths (D-007).
     */
    private fun sweepDaemonLogs(dryRun: Boolean): DaemonLogSweep {
        val dir = daemonRuntimeDir ?: return DaemonLogSweep()
        val logs: List<Path> = try {
            if (!Files.isDirectory(dir)) return DaemonLogSweep()
            Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".log") && it.isRegularFile() }
                    .sorted().toList()
            }
        } catch (e: Exception) {
            return DaemonLogSweep()
        }
        val deleted = mutableListOf<String>()
        var bytes = 0L
        for (log in logs) {
            val name = log.fileName.toString()
            if (daemonLogAlive(dir, name)) continue
            bytes += fileBytes(log)
            if (!dryRun) {
                val gone = try {
                    Files.deleteIfExists(log)
                    !Files.exists(log)
                } catch (e: Exception) {
                    false
                }
                if (!gone) continue
            }
            deleted.add(name)
        }
        return DaemonLogSweep(deleted, bytes)
    }

    /** True when the log's sibling socket has a live daemon behind it; a throwing probe reads as "cannot prove orphaned" (keep). */
    private fun daemonLogAlive(dir: Path, logName: String): Boolean {
        return try {
            val socket = dir.resolve(logName.removeSuffix(".log") + ".sock")
            if (!Files.exists(socket)) return false
            try {
                daemonSocketAlive(socket)
            } catch (e: Exception) {
                true
            }
        } catch (e: Exception) {
            true
        }
    }

    private fun normalise(path: Path): String = path.toAbsolutePath().normalize().toString()

    private fun directoryBytes(root: Path): Long {
        if (!root.isDirectory()) return 0
        return try {
            Files.walk(root).use { walk ->
                walk.filter { it.isRegularFile() }.mapToLong { fileBytes(it) }.sum()
            }
        } catch (e: IOException) {
            0
        }
    }

    private fun listFilesRecursively(root: Path): List<Path> =
        Files.walk(root).use { walk ->
            walk.filter { it.isRegularFile() }.sorted().toList()
        }

    private fun listDirsBottomUp(root: Path): List<Path> =
        Files.walk(root).use { walk ->
            walk.filter { it.isDirectory() }.sorted(Comparator.reverseOrder()).toList()
        }

    private fun fileBytes(path: Path): Long =
        try {
            Files.size(path)
        } catch (e: IOException) {
            0
        }

    private fun deleteQuietly(path: Path): Boolean =
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            false
        }

    private fun walCompanions(db: Path): List<Path> {
        val name = db.fileName.toString()
        return listOf("-wal", "-shm", "-journal").map { db.resolveSibling("$name$it") }
    }
}

/** The `ArtifactKind.JRT` row name, without depending on the artifact package's enum. */
private const val JRT_KIND: String = "JRT"

/** One `cache` command outcome: data, or a message already mapped to its exit code (D-015). */
public sealed interface CacheResult<out T> {
    public data class Ok<T>(val value: T) : CacheResult<T>

    /** [exitCode] is 4 (corrupt workspace) or 6 (DB/IO failure); never a stack trace. */
    public data class Failure(val message: String, val exitCode: Int) : CacheResult<Nothing>
}

/** `cache info` data: the one model both renderers read (D-007). */
public data class CacheInfo(
    public val dbPath: String,
    public val dbExists: Boolean,
    public val dbBytes: Long,
    /** Null when there is no database to report a version for. */
    public val schemaVersion: Int?,
    public val artifacts: Int,
    public val classes: Int,
    public val cacheBytes: Long,
)

/** One artifact `gc` deleted (or would delete under `--dry-run`). */
public data class GcDeleted(
    public val path: String,
    /** Content hash: JSON payload only, never text (D-007 keeps hashes out of prose). */
    public val hash: String,
    public val classes: Int,
)

/** `cache gc` data, deletions sorted by path (deterministic, D-007). */
public data class GcReport(
    public val deleted: List<GcDeleted>,
    public val kept: Int,
    public val dryRun: Boolean,
    /** Orphan daemon `.log` sweep (T-085); empty when the seam is off or nothing was orphaned. */
    public val daemonLogs: DaemonLogSweep = DaemonLogSweep(),
) {
    /** Classes freed (or that a real run would free). */
    public val deletedClasses: Int get() = deleted.sumOf { it.classes }
}

/**
 * Orphan daemon `.log` files `gc` deleted (or would delete under `--dry-run`).
 * File names only, sorted — never paths (D-007 keeps paths out of prose; the
 * runtime dir is machine-local anyway).
 */
public data class DaemonLogSweep(
    public val deleted: List<String> = emptyList(),
    public val bytesFreed: Long = 0,
)

/** `cache clear` data. */
public data class ClearReport(
    public val filesDeleted: Int,
    public val bytesFreed: Long,
    /** False when there was nothing to delete. */
    public val dbWasPresent: Boolean,
)

/**
 * Whole-unit human sizes (`0 B`, `6 KB`, `12 MB`) — the same rule `doctor`'s
 * cache row uses, duplicated here so `index` never depends on the CLI adapter.
 */
public fun formatCacheBytes(bytes: Long): String {
    require(bytes >= 0) { "negative size: $bytes" }
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return "$value ${units[unit]}"
}
