package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.render.JdxJson
import dev.jdx.cli.render.envelopeJson
import dev.jdx.index.cache.CacheInfo
import dev.jdx.index.cache.CacheResult
import dev.jdx.index.cache.CacheService
import dev.jdx.index.cache.ClearReport
import dev.jdx.index.cache.GcReport
import dev.jdx.index.cache.formatCacheBytes
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.system.exitProcess

/**
 * `jdx cache ...` — index-cache management (T-018, PROPOSAL.md §7.4).
 *
 * `info` reports sizes and counts, `gc` evicts artifacts no workspace
 * references (plus stale ones whose file is gone), `clear` wipes the
 * regenerable cache. Thin by rule (D-004): every policy lives in
 * [CacheService]; here is flag parsing, rendering, and exit codes only.
 * Exits 0 ok · 3 usage error · 4 corrupt workspace (gc) · 6 DB/IO failure.
 */
class CacheCommand : CoreCliktCommand(name = "cache") {
    override fun help(context: Context): String =
        "Manage the index cache: report sizes (info), evict unreferenced " +
            "artifacts (gc), wipe the regenerable cache (clear). " +
            "Everything removed is re-indexed or re-derived on demand."

    override fun run() = Unit
}

/**
 * Builds the `cache` group. [services] resolves the service for the effective
 * `--cache-dir` (production: a service over that root with the system
 * workspace store); tests inject a prebuilt service over temp dirs.
 */
fun cacheGroup(
    services: (Path) -> CacheService = { root -> CacheService(root) },
    terminate: (Int) -> Nothing = ::exitProcess,
): CacheCommand = CacheCommand().subcommands(
    CacheInfoCommand(services, terminate),
    CacheGcCommand(services, terminate),
    CacheClearCommand(services, terminate),
)

/** Test seam: a fixed service that never touches the real home directory. */
fun testCacheGroup(
    service: CacheService,
    terminate: (Int) -> Nothing = { throw CacheExit(it) },
): CacheCommand = cacheGroup(services = { service }, terminate = terminate)

/** Thrown by [testCacheGroup]'s terminator instead of killing the test JVM (L-026). */
class CacheExit(val code: Int) : RuntimeException()

/** The production cache root, mirroring `doctor`'s literal `~/.cache/jdx` (D-027). */
internal fun defaultCacheRoot(): Path =
    Path.of(System.getProperty("user.home"), ".cache", "jdx")

@Serializable
private data class CacheInfoPayload(
    val dbPath: String,
    val dbExists: Boolean,
    val dbBytes: Long,
    val schemaVersion: Int?,
    val artifacts: Int,
    val classes: Int,
    val cacheBytes: Long,
    val message: String,
)

@Serializable
private data class GcDeletedPayload(val path: String, val hash: String, val classes: Int)

@Serializable
private data class GcPayload(
    val deleted: List<GcDeletedPayload>,
    val deletedClasses: Int,
    val kept: Int,
    val dryRun: Boolean,
    val message: String,
)

@Serializable
private data class ClearPayload(val filesDeleted: Int, val bytesFreed: Long, val message: String)

private fun CacheInfoPayload.toJson(ok: Boolean): String =
    envelopeJson("cache info", ok, JdxJson.encodeToJsonElement(this))

private fun GcPayload.toJson(ok: Boolean): String =
    envelopeJson("cache gc", ok, JdxJson.encodeToJsonElement(this))

private fun ClearPayload.toJson(ok: Boolean): String =
    envelopeJson("cache clear", ok, JdxJson.encodeToJsonElement(this))

/** Prints text + optional JSON, then terminates on non-zero exit (L-026). */
private fun finishCache(text: String, jsonText: String, json: Boolean, exitCode: Int, terminate: (Int) -> Nothing) {
    if (json) println(jsonText) else println(text)
    if (exitCode != 0) terminate(exitCode)
}

/** Resolves `--cache-dir` to a usable root, or the usage error that rejects it. */
private fun resolveCacheRoot(raw: String): Result<Path> {
    val path = Path.of(raw)
    if (Files.exists(path) && !Files.isDirectory(path)) {
        return Result.failure(IllegalArgumentException("--cache-dir is not a directory: $raw"))
    }
    return Result.success(path)
}

class CacheInfoCommand(
    private val services: (Path) -> CacheService = { root -> CacheService(root) },
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "info") {
    override fun help(context: Context): String =
        "Report the index cache: database location, size and schema, artifact and " +
            "class counts, total cache size. A missing database is an empty report, " +
            "not an error. Exits 6 when the database cannot be read."

    private val cacheDir by option(
        "--cache-dir",
        help = "Cache root to report (default ~/.cache/jdx).",
    ).default(defaultCacheRoot().toString())

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        val root = resolveCacheRoot(cacheDir).getOrElse { failure ->
            val message = "usage error: ${failure.message}"
            finishCache(
                message,
                CacheInfoPayload(
                    dbPath = cacheDir, dbExists = false, dbBytes = 0, schemaVersion = null,
                    artifacts = 0, classes = 0, cacheBytes = 0, message = message,
                ).toJson(ok = false),
                json, 3, terminate,
            )
            return
        }
        when (val result = services(root).info()) {
            is CacheResult.Ok -> {
                val info = result.value
                finishCache(
                    info.renderText(root),
                    info.toPayload().toJson(ok = true),
                    json, 0, terminate,
                )
            }
            is CacheResult.Failure -> {
                finishCache(
                    result.message,
                    CacheInfoPayload(
                        dbPath = root.resolve("index/v1.db").toString(), dbExists = false,
                        dbBytes = 0, schemaVersion = null, artifacts = 0, classes = 0,
                        cacheBytes = 0, message = result.message,
                    ).toJson(ok = false),
                    json, result.exitCode, terminate,
                )
            }
        }
    }
}

class CacheGcCommand(
    private val services: (Path) -> CacheService = { root -> CacheService(root) },
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "gc") {
    override fun help(context: Context): String =
        "Evict cached artifacts no workspace references, plus stale ones whose file " +
            "is gone. Artifacts still named by a workspace are kept; the JDK index " +
            "is kept while any workspace includes the JDK. With --dry-run, report " +
            "without deleting. Exits 4 on a corrupt workspace, 6 on DB/IO failure."

    private val cacheDir by option(
        "--cache-dir",
        help = "Cache root to collect (default ~/.cache/jdx).",
    ).default(defaultCacheRoot().toString())

    private val dryRun by option(
        "--dry-run",
        help = "Report what would be deleted without deleting anything.",
    ).flag()

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        val root = resolveCacheRoot(cacheDir).getOrElse { failure ->
            val message = "usage error: ${failure.message}"
            finishCache(
                message,
                GcPayload(emptyList(), 0, 0, dryRun, message).toJson(ok = false),
                json, 3, terminate,
            )
            return
        }
        when (val result = services(root).gc(dryRun)) {
            is CacheResult.Ok -> {
                val report = result.value
                finishCache(
                    report.renderText(dryRun),
                    report.toPayload().toJson(ok = true),
                    json, 0, terminate,
                )
            }
            is CacheResult.Failure -> {
                finishCache(
                    result.message,
                    GcPayload(emptyList(), 0, 0, dryRun, result.message).toJson(ok = false),
                    json, result.exitCode, terminate,
                )
            }
        }
    }
}

class CacheClearCommand(
    private val services: (Path) -> CacheService = { root -> CacheService(root) },
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "clear") {
    override fun help(context: Context): String =
        "Wipe the regenerable cache: the index database files and the derived " +
            "project cache. An absent cache is success with nothing deleted. " +
            "Exits 6 when files cannot be deleted."

    private val cacheDir by option(
        "--cache-dir",
        help = "Cache root to wipe (default ~/.cache/jdx).",
    ).default(defaultCacheRoot().toString())

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        val root = resolveCacheRoot(cacheDir).getOrElse { failure ->
            val message = "usage error: ${failure.message}"
            finishCache(
                message,
                ClearPayload(0, 0, message).toJson(ok = false),
                json, 3, terminate,
            )
            return
        }
        when (val result = services(root).clear()) {
            is CacheResult.Ok -> {
                val report = result.value
                finishCache(
                    report.renderText(),
                    report.toPayload().toJson(ok = true),
                    json, 0, terminate,
                )
            }
            is CacheResult.Failure -> {
                finishCache(
                    result.message,
                    ClearPayload(0, 0, result.message).toJson(ok = false),
                    json, result.exitCode, terminate,
                )
            }
        }
    }
}

// -- rendering (text mirrors JSON field-for-field, D-007) --------------------

private fun CacheInfo.renderText(root: Path): String = buildString {
    appendLine("cache info")
    if (dbExists) {
        appendLine("  database: $dbPath (${formatCacheBytes(dbBytes)}, schema $schemaVersion)")
    } else {
        appendLine("  database: none (no index yet at $dbPath)")
    }
    appendLine("  artifacts: $artifacts ($classes classes)")
    append("  cache: $root (${formatCacheBytes(cacheBytes)})")
}.trimEnd()

private fun CacheInfo.toPayload(): CacheInfoPayload = CacheInfoPayload(
    dbPath = dbPath, dbExists = dbExists, dbBytes = dbBytes, schemaVersion = schemaVersion,
    artifacts = artifacts, classes = classes, cacheBytes = cacheBytes, message = "ok",
)

private fun GcReport.renderText(dryRun: Boolean): String = buildString {
    appendLine(if (dryRun) "cache gc --dry-run" else "cache gc")
    if (deleted.isEmpty()) {
        append("  nothing to collect ($kept artifact(s) referenced)")
    } else {
        val verb = if (dryRun) "would delete" else "deleted"
        appendLine("  $verb: ${deleted.size} artifact(s), $deletedClasses classes freed")
        for (entry in deleted) appendLine("    ${entry.path} (${entry.classes} classes)")
        append("  kept: $kept artifact(s)")
        if (dryRun) append("\n  re-run without --dry-run to delete")
    }
}.trimEnd()

private fun GcReport.toPayload(): GcPayload = GcPayload(
    deleted = deleted.map { GcDeletedPayload(it.path, it.hash, it.classes) },
    deletedClasses = deletedClasses, kept = kept, dryRun = dryRun, message = "ok",
)

private fun ClearReport.renderText(): String =
    if (!dbWasPresent) {
        "cache cleared\n  nothing to clear (cache is already empty)"
    } else {
        "cache cleared\n  deleted: $filesDeleted file(s), ${formatCacheBytes(bytesFreed)} freed"
    }

private fun ClearReport.toPayload(): ClearPayload =
    ClearPayload(filesDeleted, bytesFreed, if (dbWasPresent) "cleared" else "already empty")
