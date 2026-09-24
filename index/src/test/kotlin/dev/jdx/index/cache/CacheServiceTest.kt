package dev.jdx.index.cache

import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceCorruptException
import dev.jdx.index.workspace.WorkspaceDefinition
import dev.jdx.index.workspace.WorkspaceStore
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tier-1 example tests for [CacheService] (T-018).
 *
 * No SQLite, no disk: the store is [FakeIndexStore], the database presence is
 * injected, expansion is a fake over invented `/fake` paths, and the service's
 * own [CacheService.directoryBytes] reads through `isDirectory` probes that
 * answer false without touching anything. Real persistence is tier 2
 * (`CacheServiceStoreTest`); the generating family is `CacheServicePropertyTest`.
 */
class CacheServiceTest {

    @TempDir
    private lateinit var tempDir: Path

    private var daemonSeq = 0

    /** One invented-but-plausible cache layout per test; nothing here exists on disk. */
    private class Harness {
        val store = FakeIndexStore()
        val workspaces = InMemoryWorkspaceStore()
        var dbPresent = true
        val resolvable = mutableSetOf<String>()

        fun service(
            daemonDir: Path? = null,
            aliveSockets: Set<String> = emptySet(),
        ): CacheService = CacheService(
            cacheRoot = Path.of("/fake/cache"),
            workspaceStore = workspaces,
            openStore = { store },
            expandJarSpec = { spec ->
                if (spec in resolvable) listOf(Path.of(spec))
                else throw IllegalArgumentException("no such artifact: $spec")
            },
            isDbPresent = { dbPresent },
            daemonRuntimeDir = daemonDir,
            daemonSocketAlive = { socket -> socket.fileName.toString() in aliveSockets },
        )

        /** Stores an artifact and optionally makes its path resolvable. */
        fun artifact(hash: String, path: String, classes: Int, kind: String = "BINARY_JAR", exists: Boolean = true) {
            store.addArtifact(hash, path, classes, kind)
            if (exists) resolvable.add(path)
        }
    }

    private fun okInfo(service: CacheService): CacheInfo {
        val result = service.info()
        (result is CacheResult.Ok) shouldBe true
        return (result as CacheResult.Ok).value
    }

    private fun okGc(service: CacheService, dryRun: Boolean = false): GcReport {
        val result = service.gc(dryRun)
        (result is CacheResult.Ok) shouldBe true
        return (result as CacheResult.Ok).value
    }

    // -- info -----------------------------------------------------------------

    @Test
    fun `a missing database is an empty info report, not an error`() {
        val harness = Harness()
        harness.dbPresent = false
        val info = okInfo(harness.service())
        info.dbExists shouldBe false
        info.schemaVersion shouldBe null
        info.artifacts shouldBe 0
        info.classes shouldBe 0
    }

    @Test
    fun `info totals match the stored rows`() {
        val harness = Harness()
        harness.artifact("a".repeat(32), "/fake/a.jar", 3)
        harness.artifact("b".repeat(32), "/fake/b.jar", 5)
        val info = okInfo(harness.service())
        info.dbExists shouldBe true
        info.artifacts shouldBe 2
        info.classes shouldBe 8
    }

    @Test
    fun `an unreadable database is exit 6, never a throw`() {
        val failing = CacheService(
            cacheRoot = Path.of("/fake/cache"),
            workspaceStore = InMemoryWorkspaceStore(),
            openStore = { _: Path -> throw RuntimeException("disk is gone") },
            isDbPresent = { true },
        )
        val result = failing.info()
        (result is CacheResult.Failure) shouldBe true
        (result as CacheResult.Failure).exitCode shouldBe 6
        result.message shouldBe "cannot open the index database (/fake/cache/index/v1.db): disk is gone"
    }

    // -- gc -------------------------------------------------------------------

    @Test
    fun `gc keeps referenced artifacts and deletes stale ones`() {
        val harness = Harness()
        harness.artifact("a".repeat(32), "/fake/a.jar", 3)
        harness.artifact("b".repeat(32), "/fake/b.jar", 5, exists = false)
        harness.workspaces.save(WorkspaceDefinition("app", listOf("/fake/a.jar")))
        val report = okGc(harness.service())
        report.deleted.map { it.path } shouldBe listOf("/fake/b.jar")
        report.deletedClasses shouldBe 5
        report.kept shouldBe 1
        harness.store.listArtifacts().map { it.path } shouldBe listOf("/fake/a.jar")
    }

    @Test
    fun `gc deletes existing-but-unreferenced artifacts`() {
        val harness = Harness()
        harness.artifact("a".repeat(32), "/fake/a.jar", 3)
        harness.workspaces.save(WorkspaceDefinition("app", listOf("/fake/other.jar")))
        harness.resolvable.add("/fake/other.jar")
        val report = okGc(harness.service())
        report.deleted.map { it.path } shouldBe listOf("/fake/a.jar")
        report.kept shouldBe 0
    }

    @Test
    fun `gc dry-run reports without deleting`() {
        val harness = Harness()
        harness.artifact("a".repeat(32), "/fake/a.jar", 3, exists = false)
        val report = okGc(harness.service(), dryRun = true)
        report.dryRun shouldBe true
        report.deleted.map { it.path } shouldBe listOf("/fake/a.jar")
        harness.store.listArtifacts().size shouldBe 1
    }

    @Test
    fun `gc on a missing database reports nothing to collect`() {
        val harness = Harness()
        harness.dbPresent = false
        val report = okGc(harness.service())
        report.deleted shouldBe emptyList()
        report.kept shouldBe 0
    }

    @Test
    fun `gc keeps the jdk index while any workspace includes the jdk`() {
        val harness = Harness()
        harness.artifact("j".repeat(32), "jrt:/", 10, kind = "JRT")
        harness.artifact("a".repeat(32), "/fake/a.jar", 2)
        harness.workspaces.save(WorkspaceDefinition("plain", listOf("/fake/a.jar"), includeJdk = false))
        harness.workspaces.save(WorkspaceDefinition("full", listOf("/fake/a.jar"), includeJdk = true))
        val report = okGc(harness.service())
        report.deleted shouldBe emptyList()
        report.kept shouldBe 2
    }

    @Test
    fun `gc collects the jdk index when no workspace wants the jdk`() {
        val harness = Harness()
        harness.artifact("j".repeat(32), "jrt:/", 10, kind = "JRT")
        harness.workspaces.save(WorkspaceDefinition("plain", listOf(), includeJdk = false))
        val report = okGc(harness.service())
        report.deleted.map { it.path } shouldBe listOf("jrt:/")
        report.kept shouldBe 0
    }

    @Test
    fun `gc keeps the jdk index when no workspaces exist at all`() {
        val harness = Harness()
        harness.artifact("j".repeat(32), "jrt:/", 10, kind = "JRT")
        val report = okGc(harness.service())
        report.deleted shouldBe emptyList()
        report.kept shouldBe 1
    }

    @Test
    fun `gc on a corrupt workspace is exit 4 and deletes nothing`() {
        val harness = Harness()
        harness.artifact("a".repeat(32), "/fake/a.jar", 3, exists = false)
        val corrupt: WorkspaceStore = object : WorkspaceStore by InMemoryWorkspaceStore() {
            override fun listNames(): List<String> = listOf("bad")
            override fun load(name: String): WorkspaceDefinition? =
                throw WorkspaceCorruptException("workspace 'bad' is corrupt: not toml")
        }
        val service = CacheService(
            cacheRoot = Path.of("/fake/cache"),
            workspaceStore = corrupt,
            openStore = { harness.store },
            expandJarSpec = { listOf(Path.of(it)) },
            isDbPresent = { true },
        )
        val result = service.gc()
        (result is CacheResult.Failure) shouldBe true
        (result as CacheResult.Failure).exitCode shouldBe 4
        harness.store.listArtifacts().size shouldBe 1
    }

    // -- daemon log sweep (T-085) -------------------------------------------------

    /** A fresh runtime dir per call; the database is absent so only the sweep runs. */
    private fun daemonService(
        aliveSockets: Set<String> = emptySet(),
        throwingProbe: Boolean = false,
    ): Pair<CacheService, Path> {
        val harness = Harness()
        harness.dbPresent = false
        val dir = tempDir.resolve("daemon-${daemonSeq++}")
        Files.createDirectories(dir)
        val service = CacheService(
            cacheRoot = Path.of("/fake/cache"),
            workspaceStore = harness.workspaces,
            openStore = { harness.store },
            isDbPresent = { false },
            daemonRuntimeDir = dir,
            daemonSocketAlive = { socket ->
                if (throwingProbe) throw RuntimeException("probe is down")
                socket.fileName.toString() in aliveSockets
            },
        )
        return service to dir
    }

    private fun writeLog(dir: Path, name: String, bytes: Int = 10): Path {
        val file = dir.resolve(name)
        Files.write(file, ByteArray(bytes) { it.toByte() })
        return file
    }

    private fun touch(dir: Path, name: String): Path {
        val file = dir.resolve(name)
        Files.write(file, ByteArray(0))
        return file
    }

    @Test
    fun `gc deletes orphan logs and keeps the live one`() {
        val (service, dir) = daemonService(aliveSockets = setOf("live-v1.sock"))
        writeLog(dir, "orphan-v1.log", 10)
        writeLog(dir, "dead-v1.log", 20)
        touch(dir, "dead-v1.sock")
        writeLog(dir, "live-v1.log", 30)
        touch(dir, "live-v1.sock")
        val report = okGc(service)
        report.daemonLogs.deleted shouldBe listOf("dead-v1.log", "orphan-v1.log")
        report.daemonLogs.bytesFreed shouldBe 30
        Files.exists(dir.resolve("orphan-v1.log")) shouldBe false
        Files.exists(dir.resolve("dead-v1.log")) shouldBe false
        Files.exists(dir.resolve("live-v1.log")) shouldBe true
    }

    @Test
    fun `gc dry-run reports orphan logs without deleting`() {
        val (service, dir) = daemonService()
        writeLog(dir, "orphan-v1.log", 12)
        val report = okGc(service, dryRun = true)
        report.dryRun shouldBe true
        report.daemonLogs.deleted shouldBe listOf("orphan-v1.log")
        report.daemonLogs.bytesFreed shouldBe 12
        Files.exists(dir.resolve("orphan-v1.log")) shouldBe true
    }

    @Test
    fun `gc without the daemon seam sweeps no logs`() {
        val harness = Harness()
        harness.dbPresent = false
        val report = okGc(harness.service())
        report.daemonLogs shouldBe DaemonLogSweep()
    }

    @Test
    fun `gc keeps logs when the probe throws`() {
        val (service, dir) = daemonService(throwingProbe = true)
        touch(dir, "maybe-v1.sock")
        writeLog(dir, "maybe-v1.log", 8)
        val report = okGc(service)
        report.daemonLogs.deleted shouldBe emptyList()
        Files.exists(dir.resolve("maybe-v1.log")) shouldBe true
    }

    @Test
    fun `gc ignores non-log files and subdirectories`() {
        val (service, dir) = daemonService()
        Files.write(dir.resolve("notes.txt"), byteArrayOf(1))
        val sub = dir.resolve("sub")
        Files.createDirectories(sub)
        writeLog(sub, "inner.log", 5)
        val report = okGc(service)
        report.daemonLogs.deleted shouldBe emptyList()
        Files.exists(dir.resolve("notes.txt")) shouldBe true
        Files.exists(sub.resolve("inner.log")) shouldBe true
    }

    @Test
    fun `gc with a missing runtime dir sweeps nothing`() {
        val harness = Harness()
        harness.dbPresent = false
        val report = okGc(harness.service(daemonDir = tempDir.resolve("absent-${daemonSeq++}")))
        report.daemonLogs shouldBe DaemonLogSweep()
    }

    @Test
    fun `gc only sweeps the lowercase log suffix`() {
        val (service, dir) = daemonService()
        Files.write(dir.resolve("upper-V1.LOG"), byteArrayOf(4))
        val report = okGc(service)
        report.daemonLogs.deleted shouldBe emptyList()
        Files.exists(dir.resolve("upper-V1.LOG")) shouldBe true
    }

    @Test
    fun `gc sweeps artifacts and orphan logs together`() {
        val harness = Harness()
        harness.artifact("a".repeat(32), "/fake/a.jar", 3, exists = false)
        val dir = tempDir.resolve("daemon-${daemonSeq++}")
        Files.createDirectories(dir)
        writeLog(dir, "orphan-v1.log", 7)
        val report = okGc(harness.service(daemonDir = dir))
        report.deleted.map { it.path } shouldBe listOf("/fake/a.jar")
        report.daemonLogs.deleted shouldBe listOf("orphan-v1.log")
        Files.exists(dir.resolve("orphan-v1.log")) shouldBe false
    }

    // -- clear ------------------------------------------------------------------

    @Test
    fun `clear on an absent cache reports nothing to delete`() {
        val harness = Harness()
        val result = CacheService(
            cacheRoot = Path.of("/fake/nowhere-cache"),
            workspaceStore = harness.workspaces,
            openStore = { harness.store },
        ).clear()
        (result is CacheResult.Ok) shouldBe true
        val report = (result as CacheResult.Ok).value
        report.filesDeleted shouldBe 0
        report.bytesFreed shouldBe 0
        report.dbWasPresent shouldBe false
    }

    // -- formatting ---------------------------------------------------------------

    @Test
    fun `cache sizes render in whole units`() {
        formatCacheBytes(0) shouldBe "0 B"
        formatCacheBytes(1023) shouldBe "1023 B"
        formatCacheBytes(1024) shouldBe "1 KB"
        formatCacheBytes(6 * 1024) shouldBe "6 KB"
        formatCacheBytes(12 * 1024 * 1024) shouldBe "12 MB"
    }
}
