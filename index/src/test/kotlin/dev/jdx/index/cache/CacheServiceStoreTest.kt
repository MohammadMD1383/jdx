package dev.jdx.index.cache

import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.index.ArtifactIndexer
import dev.jdx.index.store.NewArtifact
import dev.jdx.index.store.SqliteSchemaVersion
import dev.jdx.index.store.sqlite.SqliteIndexStore
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tier-2 tests for [CacheService] (T-018): the real SQLite store over a real
 * indexed fixture jar on a `@TempDir` cache root.
 *
 * Policy itself (reference sets, JRT rules, idempotence) is pinned in tier 1
 * (`CacheServiceTest`, `CacheServicePropertyTest`); here the assertions are
 * that the policy survives real persistence — counts come back from SQLite,
 * deletions remove rows, `clear` removes files — plus the real-filesystem
 * glob expansion path `gc` uses for workspace references.
 */
@Tag("tier2")
class CacheServiceStoreTest {

    @TempDir
    private lateinit var tempDir: Path

    private fun cacheRoot(): Path = tempDir.resolve("cache")

    private fun dbFile(): Path = cacheRoot().resolve("index/v1.db")

    private fun service(): CacheService =
        CacheService(cacheRoot(), FileWorkspaceStore(tempDir.resolve("config")))

    /** Indexes the fixture jar into a fresh store at [dbFile]; returns the jar path. */
    private fun indexFixtureJar(): String {
        val jar = ArtifactTestJars.binaryJar().absolutePath
        SqliteIndexStore.open(dbFile()).use { store ->
            ArtifactIndexer.indexOne(store, Path.of(jar))
        }
        return jar
    }

    private fun infoOk(service: CacheService): CacheInfo {
        val result = service.info()
        (result is CacheResult.Ok) shouldBe true
        return (result as CacheResult.Ok).value
    }

    private fun gcOk(service: CacheService, dryRun: Boolean = false): GcReport {
        val result = service.gc(dryRun)
        (result is CacheResult.Ok) shouldBe true
        return (result as CacheResult.Ok).value
    }

    @Test
    fun `info reports the indexed jar from sqlite`() {
        indexFixtureJar()
        val info = infoOk(service())
        info.dbExists shouldBe true
        info.schemaVersion shouldBe SqliteSchemaVersion.CURRENT
        info.artifacts shouldBe 1
        (info.classes > 0) shouldBe true
        (info.dbBytes > 0) shouldBe true
    }

    @Test
    fun `gc keeps the jar named by a workspace and collects only the stale row`() {
        val jar = indexFixtureJar()
        SqliteIndexStore.open(dbFile()).use { store ->
            store.upsertArtifact(NewArtifact(hash = "0".repeat(32), path = "/nonexistent/ghost.jar"))
        }
        val service = service()
        FileWorkspaceStore(tempDir.resolve("config")).save(WorkspaceDefinition("app", listOf(jar)))
        val report = gcOk(service)
        report.deleted.map { it.path } shouldBe listOf("/nonexistent/ghost.jar")
        report.kept shouldBe 1
        SqliteIndexStore.open(dbFile()).use { store ->
            store.listArtifacts().size shouldBe 1
        }
    }

    @Test
    fun `gc dry-run keeps the stale row`() {
        val jar = indexFixtureJar()
        SqliteIndexStore.open(dbFile()).use { store ->
            store.upsertArtifact(NewArtifact(hash = "0".repeat(32), path = "/nonexistent/ghost.jar"))
        }
        FileWorkspaceStore(tempDir.resolve("config")).save(WorkspaceDefinition("app", listOf(jar)))
        val report = gcOk(service(), dryRun = true)
        report.dryRun shouldBe true
        report.deleted.map { it.path } shouldBe listOf("/nonexistent/ghost.jar")
        SqliteIndexStore.open(dbFile()).use { store ->
            store.listArtifacts().size shouldBe 2
        }
    }

    @Test
    fun `gc dry-run twice is byte-identical`() {
        indexFixtureJar()
        val service = service()
        // No workspaces: the jar is unreferenced, so the dry-run names it twice identically.
        gcOk(service, dryRun = true) shouldBe gcOk(service, dryRun = true)
    }

    @Test
    fun `clear wipes the database and a following info is empty`() {
        indexFixtureJar()
        val service = service()
        val cleared = when (val result = service.clear()) {
            is CacheResult.Ok -> result.value
            is CacheResult.Failure -> throw AssertionError("clear failed: ${result.message}")
        }
        (cleared.filesDeleted >= 1) shouldBe true
        (cleared.bytesFreed > 0) shouldBe true
        val info = infoOk(service)
        info.dbExists shouldBe false
        info.artifacts shouldBe 0
        val again = when (val result = service.clear()) {
            is CacheResult.Ok -> result.value
            is CacheResult.Failure -> throw AssertionError("clear failed: ${result.message}")
        }
        again.filesDeleted shouldBe 0
        again.dbWasPresent shouldBe false
    }

    @Test
    fun `a corrupt database is exit 6 with the path named`() {
        val db = dbFile()
        db.toFile().parentFile.mkdirs()
        db.toFile().writeText("this is not sqlite")
        val result = service().info()
        (result is CacheResult.Failure) shouldBe true
        (result as CacheResult.Failure).exitCode shouldBe 6
        result.message shouldContain db.toString()
    }
}
