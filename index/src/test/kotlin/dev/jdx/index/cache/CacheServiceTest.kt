package dev.jdx.index.cache

import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceCorruptException
import dev.jdx.index.workspace.WorkspaceDefinition
import dev.jdx.index.workspace.WorkspaceStore
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import org.junit.jupiter.api.Test

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

    /** One invented-but-plausible cache layout per test; nothing here exists on disk. */
    private class Harness {
        val store = FakeIndexStore()
        val workspaces = InMemoryWorkspaceStore()
        var dbPresent = true
        val resolvable = mutableSetOf<String>()

        fun service(): CacheService = CacheService(
            cacheRoot = Path.of("/fake/cache"),
            workspaceStore = workspaces,
            openStore = { store },
            expandJarSpec = { spec ->
                if (spec in resolvable) listOf(Path.of(spec))
                else throw IllegalArgumentException("no such artifact: $spec")
            },
            isDbPresent = { dbPresent },
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
