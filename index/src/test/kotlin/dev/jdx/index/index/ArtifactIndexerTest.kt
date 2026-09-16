package dev.jdx.index.index

import dev.jdx.core.model.WarningCode
import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.asm.AsmClassReader
import dev.jdx.index.asm.ClassReadResult
import dev.jdx.index.store.sqlite.SqliteIndexStore
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.nio.file.Path
import java.util.Collections
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tier-2 tests for [ArtifactIndexer] (T-014, TESTING.md §13).
 *
 * Tagged `tier2` (docs/TESTING.md §2): every test opens real jars and a real
 * SQLite file on disk. The generative bar (TESTING.md §13) is carried by two
 * family members here: a metamorphic determinism test (index the same inputs
 * into two stores ⟹ identical rows) and a fault-injection sweep (a class cut
 * at every 10 % boundary still indexes without throwing).
 */
@Tag("tier2")
class ArtifactIndexerTest {

    private val binaryJar: File by lazy { ArtifactTestJars.binaryJar() }

    private fun openStore(dir: Path, name: String = "index.db"): SqliteIndexStore =
        SqliteIndexStore.open(dir.resolve(name))

    private fun entryToBinary(entry: String): String =
        entry.removeSuffix(".class").replace('/', '.')

    // -- single-artifact behaviour --------------------------------------------

    @Test
    fun `indexing the fixture jar stores every class exactly as ASM reads it`(@TempDir temp: Path) {
        openStore(temp).use { store ->
            val result = ArtifactIndexer.indexOne(store, binaryJar.toPath())
            result.status shouldBe EntryStatus.INDEXED
            (result.classCount > 0) shouldBe true
            result.artifactId shouldNotBe null
            val id = result.artifactId!!
            store.classCount(id) shouldBe result.classCount
            // Every stored class is byte-identical to a live ASM read — the store
            // adds no interpretation of its own.
            ArtifactLoader.openJar(binaryJar.toPath()).use { root ->
                for (entry in root.classEntryPaths()) {
                    val binary = entryToBinary(entry)
                    val bytes = root.openClass(entry).use { it.readBytes() }
                    val expected = (AsmClassReader.read(bytes, binary) as ClassReadResult.Ok).info
                    store.loadClass(id, binary) shouldBe expected
                }
            }
        }
        // Full-jar bytes were parsed, never loaded (D-017): the static-init probe
        // must not have fired even after indexing the whole fixture jar.
        File(System.getProperty("java.io.tmpdir"), "jdx-fixture-static-init-marker").exists() shouldBe false
    }

    @Test
    fun `indexing the same jar twice short-circuits the second run`(@TempDir temp: Path) {
        openStore(temp).use { store ->
            val first = ArtifactIndexer.indexOne(store, binaryJar.toPath())
            first.status shouldBe EntryStatus.INDEXED
            val second = ArtifactIndexer.indexOne(store, binaryJar.toPath())
            second.status shouldBe EntryStatus.SKIPPED
            second.artifactId shouldBe first.artifactId
            second.hash shouldBe first.hash
            second.classCount shouldBe first.classCount
            // The stored rows survived untouched: same sample class, same bytes.
            val sample = "dev.jdx.fixtures.Generics"
            val firstId = requireNotNull(first.artifactId)
            val secondId = requireNotNull(second.artifactId)
            store.loadClass(firstId, sample) shouldBe
                store.loadClass(secondId, sample)
            store.listClassFqns(firstId) shouldBe
                store.listClassFqns(secondId)
        }
    }

    @Test
    fun `an empty jar indexes as zero classes and then short-circuits`(@TempDir temp: Path) {
        val jar = temp.resolve("empty.jar")
        ArtifactTestJars.craftJar(
            jar,
            mapOf("META-INF/MANIFEST.MF" to ArtifactTestJars.manifestBytes(false)),
        )
        openStore(temp).use { store ->
            val first = ArtifactIndexer.indexOne(store, jar)
            first.status shouldBe EntryStatus.INDEXED
            first.classCount shouldBe 0
            val second = ArtifactIndexer.indexOne(store, jar)
            second.status shouldBe EntryStatus.SKIPPED
            second.classCount shouldBe 0
        }
    }

    // -- fault injection: one bad entry never aborts its artifact ---------------

    @Test
    fun `a corrupt entry is skipped with a CORRUPT_CLASS warning naming it`(@TempDir temp: Path) {
        val goodEntry = "dev/jdx/fixtures/Generics.class"
        val goodBytes = ArtifactTestJars.fixtureClassBytes(binaryJar, goodEntry)
        val jar = temp.resolve("half-broken.jar")
        ArtifactTestJars.craftJar(
            jar,
            mapOf(
                goodEntry to goodBytes,
                "com/example/Broken.class" to "this is not a class file".toByteArray(),
            ),
        )
        openStore(temp).use { store ->
            val result = ArtifactIndexer.indexOne(store, jar)
            result.status shouldBe EntryStatus.INDEXED
            result.classCount shouldBe 1
            val corrupts = result.warnings.filter { it.code == WarningCode.CORRUPT_CLASS }
            corrupts.size shouldBe 1
            (corrupts.single().subject?.contains("Broken") ?: false) shouldBe true
            store.loadClass(result.artifactId!!, "dev.jdx.fixtures.Generics") shouldNotBe null
        }
    }

    @Test
    fun `a future-version class yields UNSUPPORTED_CLASS_VERSION, not a failure`(@TempDir temp: Path) {
        val entry = "dev/jdx/fixtures/Generics.class"
        val bytes = ArtifactTestJars.fixtureClassBytes(binaryJar, entry).copyOf()
        // Class-file major lives at bytes 6-7; 999 is newer than any running JDK.
        bytes[6] = 0x03
        bytes[7] = 0xE7.toByte()
        val jar = temp.resolve("future.jar")
        ArtifactTestJars.craftJar(jar, mapOf(entry to bytes))
        openStore(temp).use { store ->
            val result = ArtifactIndexer.indexOne(store, jar)
            result.status shouldBe EntryStatus.INDEXED
            result.classCount shouldBe 0
            val unsupported = result.warnings.filter {
                it.code == WarningCode.UNSUPPORTED_CLASS_VERSION
            }
            unsupported.size shouldBe 1
            unsupported.single().subject shouldBe "dev.jdx.fixtures.Generics"
        }
    }

    @Test
    fun `a class cut at every 10 percent boundary still indexes without throwing`(@TempDir temp: Path) {
        // Fault-injection sweep (TESTING.md §7): truncation is generated at fixed
        // fractions, not hand-picked. The invariant is survival — every cut
        // indexes (possibly with warnings), none throws, none fails the artifact.
        val entry = "dev/jdx/fixtures/Generics.class"
        val full = ArtifactTestJars.fixtureClassBytes(binaryJar, entry)
        openStore(temp).use { store ->
            for (percent in 10..100 step 10) {
                val size = (full.size * percent) / 100
                val jar = temp.resolve("cut-$percent.jar")
                ArtifactTestJars.craftJar(jar, mapOf(entry to full.copyOf(size)))
                val result = ArtifactIndexer.indexOne(store, jar)
                result.status shouldBe EntryStatus.INDEXED
            }
            // The uncut control stores cleanly with no warnings.
            val control = ArtifactIndexer.indexOne(store, temp.resolve("cut-100.jar"))
            control.classCount shouldBe 1
            control.warnings shouldBe emptyList()
        }
    }

    // -- parallel runs ----------------------------------------------------------

    @Test
    fun `indexMany indexes several jars and reports them sorted by path`(@TempDir temp: Path) {
        val goodEntry = "dev/jdx/fixtures/Generics.class"
        val goodBytes = ArtifactTestJars.fixtureClassBytes(binaryJar, goodEntry)
        val broken = temp.resolve("b-broken.jar")
        ArtifactTestJars.craftJar(
            broken,
            mapOf(
                goodEntry to goodBytes,
                "com/example/Broken.class" to byteArrayOf(0, 1, 2, 3),
            ),
        )
        openStore(temp).use { store ->
            val seen = Collections.synchronizedList(mutableListOf<String>())
            val report = ArtifactIndexer.indexMany(
                store,
                listOf(binaryJar.toPath(), broken),
            ) { result -> seen.add(result.path) }
            report.results.map { it.path } shouldBe report.results.map { it.path }.sorted()
            report.indexedCount shouldBe 2
            report.failedCount shouldBe 0
            (report.totalClasses > 0) shouldBe true
            // The progress listener heard about every artifact exactly once.
            seen.sorted() shouldBe report.results.map { it.path }
        }
    }

    @Test
    fun `indexMany keeps a missing path from failing its neighbours`(@TempDir temp: Path) {
        openStore(temp).use { store ->
            val missing = temp.resolve("nope.jar")
            val report = ArtifactIndexer.indexMany(store, listOf(binaryJar.toPath(), missing))
            report.results.size shouldBe 2
            report.indexedCount shouldBe 1
            report.failedCount shouldBe 1
            val failed = report.failures.single()
            (failed.path.contains("nope.jar")) shouldBe true
            (failed.error?.isNotEmpty() ?: false) shouldBe true
            val indexed = report.results.single { it.status == EntryStatus.INDEXED }
            (indexed.classCount > 0) shouldBe true
        }
    }

    @Test
    fun `indexMany of nothing is an empty report`(@TempDir temp: Path) {
        openStore(temp).use { store ->
            val report = ArtifactIndexer.indexMany(store, emptyList())
            report.results shouldBe emptyList()
            report.totalClasses shouldBe 0
        }
    }

    @Test
    fun `parallel indexing is deterministic across stores`(@TempDir temp: Path) {
        // Metamorphic relation (TESTING.md §6): index the same inputs twice
        // (here: into two fresh stores) ⟹ identical rows.
        val goodEntry = "dev/jdx/fixtures/Generics.class"
        val goodBytes = ArtifactTestJars.fixtureClassBytes(binaryJar, goodEntry)
        val mixed = temp.resolve("mixed.jar")
        ArtifactTestJars.craftJar(
            mixed,
            mapOf(
                goodEntry to goodBytes,
                "com/example/Broken.class" to byteArrayOf(9, 9, 9),
            ),
        )
        val inputs = listOf(binaryJar.toPath(), mixed)
        fun snapshot(storeFile: String): Map<String, List<String>> {
            openStore(temp, storeFile).use { store ->
                val report = ArtifactIndexer.indexMany(store, inputs)
                report.failedCount shouldBe 0
                return buildMap {
                    for (result in report.results) {
                        val id = result.artifactId!!
                        put(
                            result.hash!!,
                            store.listClassFqns(id) +
                                store.loadClass(id, "dev.jdx.fixtures.Generics").toString(),
                        )
                    }
                }
            }
        }
        // Statuses agree run to run as well (all INDEXED on fresh stores).
        snapshot("a.db") shouldBe snapshot("b.db")
    }
}
