package dev.jdx.index.cache

import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Generative tests for the T-085 daemon `.log` sweep in [CacheService.gc].
 *
 * Whatever layout the fuzzer invents — logs with and without sockets, live
 * and dead daemons, dry runs — these laws hold: deletions are the sorted
 * orphan names only, freed bytes tally the deleted files, live-daemon logs
 * and bystander files survive, a dry run never mutates, and a real follow-up
 * run collects exactly what a dry run promised (idempotence). 200 cases over
 * real `@TempDir` layouts; the never-throws law is structural (any throw
 * fails the case).
 */
class DaemonLogSweepPropertyTest {

    @TempDir
    private lateinit var tempDir: Path

    private var seq = 0

    private data class Case(
        val logs: List<String>,
        val sockets: List<String>,
        val alive: List<String>,
        val dryRun: Boolean,
    )

    private val baseArb: Arb<String> = Arb.of("a", "b", "c", "Aa", "odd name", "v9")

    private val caseArb: Arb<Case> = Arb.bind(
        Arb.list(baseArb, 0..4),
        Arb.list(Arb.boolean(), 0..6),
        Arb.list(Arb.boolean(), 0..6),
        Arb.boolean(),
    ) { bases, socketFlags, aliveFlags, dryRun ->
        val logs = bases.distinct().map { "$it-v1.log" }
        val socks = logs.map { it.removeSuffix(".log") + ".sock" }
        Case(
            logs = logs,
            sockets = socks.filterIndexed { index, _ -> socketFlags.getOrElse(index) { false } },
            alive = socks.filterIndexed { index, _ -> aliveFlags.getOrElse(index) { false } },
            dryRun = dryRun,
        )
    }

    private fun CacheService.gcOk(dryRun: Boolean): GcReport {
        val result = gc(dryRun)
        (result is CacheResult.Ok) shouldBe true
        return (result as CacheResult.Ok).value
    }

    @Test
    fun `the daemon sweep is sorted, conservative and idempotent`() = runBlocking<Unit> {
        checkAll(200, caseArb) { case ->
            val dir = Files.createDirectories(tempDir.resolve("case-${seq++}"))
            val sizes = mutableMapOf<String, Int>()
            case.logs.forEachIndexed { index, name ->
                val size = (index % 5) + 1
                sizes[name] = size
                Files.write(dir.resolve(name), ByteArray(size))
            }
            case.sockets.forEach { Files.write(dir.resolve(it), ByteArray(0)) }
            Files.write(dir.resolve("notes.txt"), byteArrayOf(1))
            val sub = Files.createDirectories(dir.resolve("sub"))
            Files.write(sub.resolve("inner.log"), byteArrayOf(2))

            val alive = case.alive.toSet()
            val service = CacheService(
                cacheRoot = Path.of("/fake/cache"),
                workspaceStore = InMemoryWorkspaceStore(),
                openStore = { FakeIndexStore() },
                isDbPresent = { false },
                daemonRuntimeDir = dir,
                daemonSocketAlive = { socket -> socket.fileName.toString() in alive },
            )
            val expected = case.logs.filter { log ->
                val sock = log.removeSuffix(".log") + ".sock"
                sock !in case.sockets || sock !in alive
            }.sorted()

            val report = service.gcOk(case.dryRun)
            report.daemonLogs.deleted shouldBe expected
            report.daemonLogs.bytesFreed shouldBe expected.sumOf { sizes.getValue(it) }
            // Bystanders survive every run.
            Files.exists(dir.resolve("notes.txt")) shouldBe true
            Files.exists(sub.resolve("inner.log")) shouldBe true

            // A real follow-up collects exactly what a dry run promised, else nothing.
            val rerun = service.gcOk(dryRun = false)
            if (case.dryRun) {
                rerun.daemonLogs.deleted shouldBe expected
            } else {
                rerun.daemonLogs.deleted shouldBe emptyList()
            }
            for (name in expected) Files.exists(dir.resolve(name)) shouldBe false
            for (name in case.logs - expected.toSet()) Files.exists(dir.resolve(name)) shouldBe true
        }
    }
}
