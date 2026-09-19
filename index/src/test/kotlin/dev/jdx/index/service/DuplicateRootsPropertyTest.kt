package dev.jdx.index.service

import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome.SearchList
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Generative family for root dedupe (T-068, TESTING.md §4 — the task's *generating*
 * family alongside the [DuplicateRootsTest] examples).
 *
 * Two laws, generated rather than enumerated:
 * 1. **Dedupe** — any number of redundant path decorations (`.`/`..` segments) of the
 *    same file still behaves like *one* root: search output byte-identical to the
 *    single-root answer, one hit.
 * 2. **Shading visibility** — N copies of the same bytes in N *distinct* directories
 *    stay N providers: N hits. Dedupe must key on the resolved path, never the name.
 *
 * Tier 2: both read the real fixture jar (no real jars in tier 1, TESTING.md §2).
 */
@Tag("tier2")
class DuplicateRootsPropertyTest {

    private val ref = "dev.jdx.fixtures.Generics"

    /** One redundant segment inserted above the file, normalising away again. */
    private val step = Arb.of(listOf(".", ".."))

    private fun searchHits(roots: RootsSpec): Int {
        val outcome = JdxService.search(ref, roots)
        outcome.exitCode shouldBe 0
        return (outcome as SearchList).listing.hits.size
    }

    @Test
    fun `redundant path forms of one jar behave like a single root`() = runBlocking<Unit> {
        val jar = FixtureJars.binaryJar().toPath()
        val single = JdxService.search(ref, RootsSpec(listOf(jar.toString()), includeJdk = false))
        (single.exitCode) shouldBe 0
        val singleText = single.renderText(false)

        checkAll(500, Arb.list(step, 1..2)) { steps ->
            // Redundant decorations of the base directory, each derived from the
            // ORIGINAL jar (never from an already-decorated path — `A/.`'s parent is
            // `A/.`, and composing hops there lands on absent paths): A/file.jar →
            // A/./file.jar and A/file.jar → A/../A/file.jar. All generated forms go
            // into the spec together with the plain path; every one must dedupe.
            val dir = jar.parent
            val forms = steps.map { one ->
                if (one == ".") {
                    dir.resolve(".").resolve(jar.fileName)
                } else {
                    dir.resolve("..").resolve(dir.fileName).resolve(jar.fileName)
                }
            }
            val roots = RootsSpec(
                jarSpecs = listOf(jar.toString()) + forms.map { it.toString() },
                includeJdk = false,
            )
            val outcome = JdxService.search(ref, roots)
            outcome.exitCode shouldBe 0
            outcome.renderText(false) shouldBe singleText
            searchHits(roots) shouldBe 1
        }
    }

    @Test
    fun `copies of one jar in generated distinct directories stay per-provider`() = runBlocking<Unit> {
        val jar = FixtureJars.binaryJar().toPath()
        checkAll(500, Arb.int(1..4)) { copies ->
            val dir = Files.createTempDirectory("jdx-shade")
            try {
                val specs = (1..copies).map { index ->
                    val nested = Files.createDirectories(dir.resolve("d$index"))
                    val target = nested.resolve(jar.fileName)
                    Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING)
                    target.toString()
                }
                searchHits(RootsSpec(jarSpecs = specs, includeJdk = false)) shouldBe copies
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }
}
