package dev.jdx.index.cache

import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative tests for [CacheService] (T-018, TESTING.md §2 family 2 — the
 * task's *generating* family alongside the example suite).
 *
 * Whatever the fuzzer invents — overlapping specs, dangling paths, JRT rows,
 * empty workspaces — these laws hold: `info` totals match the store, a
 * dry-run never mutates, `gc` is idempotent, and every deletion/retention
 * agrees with the reference set. All over [FakeIndexStore] and invented
 * `/fake` paths: no SQLite, no disk, 1,000 cases each.
 */
class CacheServicePropertyTest {

    private data class GenArtifact(val path: String, val classes: Int, val jrt: Boolean)
    private data class GenWorkspace(val specs: List<String>, val includeJdk: Boolean)
    private data class Case(
        val artifacts: List<GenArtifact>,
        val workspaces: List<GenWorkspace>,
        val resolvable: Set<String>,
    )

    private val poolPaths: List<String> = (0..7).map { "/fake/lib$it.jar" }
    private val danglingPaths: List<String> = (0..3).map { "/fake/dangling$it.jar" }

    private val artifactArb: Arb<GenArtifact> = Arb.bind(
        Arb.of(*(poolPaths + "jrt:/").toTypedArray()),
        Arb.int(0..5),
    ) { path, classes -> GenArtifact(path, classes, jrt = path == "jrt:/") }

    private val workspaceArb: Arb<GenWorkspace> = Arb.bind(
        Arb.list(Arb.of(*(poolPaths + danglingPaths).toTypedArray()), 0..3),
        Arb.boolean(),
    ) { specs, includeJdk -> GenWorkspace(specs, includeJdk) }

    private val caseArb: Arb<Case> = Arb.bind(
        Arb.list(artifactArb, 0..6),
        Arb.list(workspaceArb, 0..3),
        Arb.list(Arb.boolean(), 0..8),
    ) { artifacts, workspaces, presence ->
        // Duplicate pool paths collapse (one row per path, like content hashes);
        // JRT appears at most once.
        val seen = mutableSetOf<String>()
        Case(
            artifacts.filter { seen.add(it.path) },
            workspaces,
            poolPaths.filterIndexed { index, _ -> presence.getOrElse(index) { false } }.toSet(),
        )
    }

    /** Builds the case: fake store, workspace store, and the matching expansion fake. */
    private fun buildService(case: Case): Pair<CacheService, FakeIndexStore> {
        val store = FakeIndexStore()
        val workspaces = InMemoryWorkspaceStore()
        case.artifacts.forEachIndexed { index, artifact ->
            store.addArtifact(
                hash = "%032d".format(index),
                path = artifact.path,
                classCount = artifact.classes,
                kind = if (artifact.jrt) "JRT" else "BINARY_JAR",
            )
        }
        case.workspaces.forEachIndexed { index, workspace ->
            workspaces.save(WorkspaceDefinition("ws$index", workspace.specs, workspace.includeJdk))
        }
        val service = CacheService(
            cacheRoot = Path.of("/fake/cache"),
            workspaceStore = workspaces,
            openStore = { store },
            expandJarSpec = { spec ->
                if (spec in case.resolvable) listOf(Path.of(spec))
                else throw IllegalArgumentException("no such artifact: $spec")
            },
            isDbPresent = { true },
        )
        return service to store
    }

    private fun CacheService.infoOk(): CacheInfo {
        val result = info()
        (result is CacheResult.Ok) shouldBe true
        return (result as CacheResult.Ok).value
    }

    private fun CacheService.gcOk(dryRun: Boolean = false): GcReport {
        val result = gc(dryRun)
        (result is CacheResult.Ok) shouldBe true
        return (result as CacheResult.Ok).value
    }

    @Test
    fun `info totals match the stored rows`() = runBlocking<Unit> {
        checkAll(1_000, caseArb) { case ->
            val (service, _) = buildService(case)
            val info = service.infoOk()
            info.artifacts shouldBe case.artifacts.size
            info.classes shouldBe case.artifacts.sumOf { it.classes }
        }
    }

    @Test
    fun `a dry-run never mutates the store and is deterministic`() = runBlocking<Unit> {
        checkAll(1_000, caseArb) { case ->
            val (service, store) = buildService(case)
            val before = store.listArtifacts()
            val first = service.gcOk(dryRun = true)
            val second = service.gcOk(dryRun = true)
            first shouldBe second
            store.listArtifacts() shouldBe before
        }
    }

    @Test
    fun `gc is idempotent and agrees with the reference set`() = runBlocking<Unit> {
        checkAll(1_000, caseArb) { case ->
            val (service, store) = buildService(case)
            val referenced = case.workspaces.flatMap { it.specs }.filter { it in case.resolvable }.toSet()
            val keepJrt = case.workspaces.isEmpty() || case.workspaces.any { it.includeJdk }

            val report = service.gcOk()
            // Every deletion is genuinely unreferenced; every retention is referenced.
            for (deleted in report.deleted) {
                if (deleted.path == "jrt:/") {
                    keepJrt shouldBe false
                } else {
                    (deleted.path in referenced) shouldBe false
                }
            }
            val remaining = store.listArtifacts().map { it.path }.toSet()
            for (path in remaining) {
                if (path == "jrt:/") {
                    keepJrt shouldBe true
                } else {
                    (path in referenced) shouldBe true
                }
            }
            // The second run collects nothing and keeps everything that remains.
            val rerun = service.gcOk()
            rerun.deleted shouldBe emptyList()
            rerun.kept shouldBe remaining.size
            // Info still tallies the survivors.
            service.infoOk().classes shouldBe store.listArtifacts().sumOf { store.classCount(it.id) }
        }
    }
}
