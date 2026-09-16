package dev.jdx.index.index

import dev.jdx.index.store.sqlite.SqliteIndexStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tier-3 scale validation for [ArtifactIndexer] (T-014).
 *
 * Tagged `soak`, never `tier2` (docs/TESTING.md §2): indexing the whole
 * running JDK takes ~15 s on this machine and would triple `./gradlew check`.
 * Runs via `./gradlew soak`. The JDK is always present (it is the runtime), so
 * unlike corpus soak tests this one never skips.
 *
 * Measured on 2026-09-16 (JDK 26, 33,104 entries): open 0.7 s, ASM read pass
 * 5.2–6.4k classes/s (beats the §10.5 target), SQLite write pass ~4.3k
 * classes/s after the `BulkWriter` statement-reuse fix, ~13.5 s end to end.
 */
@Tag("soak")
class ArtifactIndexerSoakTest {

    @Test
    fun `the running JDK indexes with java_lang_Object loadable`(@TempDir temp: Path) {
        SqliteIndexStore.open(temp.resolve("jdk.db")).use { store ->
            val result = ArtifactIndexer.indexJdk(store)
            result.status shouldBe EntryStatus.INDEXED
            (result.classCount > 1000) shouldBe true
            println(
                "SOAK indexed ${result.classCount} JDK classes in ${result.elapsedMs}ms " +
                    "= ${"%.0f".format(result.classesPerSecond)}/s, " +
                    "warnings=${result.warnings.size}",
            )
            store.loadClass(requireNotNull(result.artifactId), "java.lang.Object") shouldNotBe null
            // Second run is a pure short-circuit: same id, same count, no warnings.
            val again = ArtifactIndexer.indexJdk(store)
            again.status shouldBe EntryStatus.SKIPPED
            again.artifactId shouldBe result.artifactId
            again.classCount shouldBe result.classCount
        }
    }
}
