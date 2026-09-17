package dev.jdx.index.service

import dev.jdx.core.render.DEFAULT_SEARCH_LIMIT
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.SearchKindFilter
import dev.jdx.index.service.JdxService.SearchOptions
import dev.jdx.index.service.JdxService.ServiceOutcome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Behaviour of `search`, `resolve`, `ls` and `tree` against real artifacts
 * (T-017, tier 2): the fixture corpus jar plus the running JDK.
 *
 * Rendering itself is pinned by `SearchGoldenTest` in the render package; here
 * the assertions are structural — matching modes, kind filters, exit codes,
 * truncation, determinism and text⊆JSON — so content drift stays in goldens.
 */
@Tag("tier2")
class SearchServiceTest {

    private fun fixtureJar(): File {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: fail("jdx.fixturesDir not set (index build wires it; see index/build.gradle.kts)")
        return dir.listFiles { file ->
            file.isFile && file.name.startsWith("testfixtures-") && !file.name.endsWith("-sources.jar")
        }?.singleOrNull() ?: fail("expected exactly one binary fixture jar in $dir")
    }

    private fun fixtureRoots(): RootsSpec =
        RootsSpec(jarSpecs = listOf(fixtureJar().absolutePath), includeJdk = false)

    private fun textOf(outcome: ServiceOutcome): String = outcome.renderText(false)

    private fun searchHits(outcome: ServiceOutcome): List<String> =
        textOf(outcome).lines().filter { it.startsWith("  ") }

    // -- search: matching modes ------------------------------------------------

    @Test
    fun `glob finds a fixture type by substring`() {
        val outcome = JdxService.search("*Generic*", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "dev.jdx.fixtures.Generics"
    }

    @Test
    fun `exact fqn returns exactly one hit`() {
        val outcome = JdxService.search("dev.jdx.fixtures.Generics", fixtureRoots())
        outcome.exitCode shouldBe 0
        val hits = searchHits(outcome)
        hits.size shouldBe 1
        hits.single() shouldContain "dev.jdx.fixtures.Generics"
    }

    @Test
    fun `camel humps find the fixture`() {
        val outcome = JdxService.search("CovOver", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "dev.jdx.fixtures.CovariantOverrides"
    }

    @Test
    fun `a hopeless pattern exits 1`() {
        val outcome = JdxService.search("ZzzQqxNope", fixtureRoots())
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "not found: ZzzQqxNope"
    }

    @Test
    fun `regex matches and invalid regex is a usage error`() {
        val found = JdxService.search(
            ".*Record$",
            fixtureRoots(),
            SearchOptions(kind = SearchKindFilter.RECORD, regex = true),
        )
        found.exitCode shouldBe 0
        textOf(found) shouldContain "dev.jdx.fixtures.PersonRecord"

        val broken = JdxService.search("([", fixtureRoots(), SearchOptions(regex = true))
        broken.exitCode shouldBe 3
        textOf(broken) shouldContain "invalid --regex"
    }

    // -- search: kind filters ---------------------------------------------------

    @Test
    fun `kind class keeps classes and drops interfaces`() {
        val outcome = JdxService.search(
            "*",
            fixtureRoots(),
            SearchOptions(kind = SearchKindFilter.CLASS, limit = 1000),
        )
        outcome.exitCode shouldBe 0
        val kinds = searchHits(outcome).map { it.trim().substringBefore(' ') }.toSet()
        kinds shouldBe setOf("class")
    }

    @Test
    fun `kind interface finds only interfaces`() {
        val outcome = JdxService.search(
            "*",
            fixtureRoots(),
            SearchOptions(kind = SearchKindFilter.INTERFACE, limit = 1000),
        )
        outcome.exitCode shouldBe 0
        val kinds = searchHits(outcome).map { it.trim().substringBefore(' ') }.toSet()
        kinds shouldBe setOf("interface")
    }

    @Test
    fun `kind method scans member names with canonical refs`() {
        val outcome = JdxService.search(
            "identity",
            fixtureRoots(),
            SearchOptions(kind = SearchKindFilter.METHOD),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "dev.jdx.fixtures.Generics#identity("
    }

    @Test
    fun `kind field lists fields only`() {
        val outcome = JdxService.search(
            "*",
            fixtureRoots(),
            SearchOptions(kind = SearchKindFilter.FIELD, limit = 1000),
        )
        outcome.exitCode shouldBe 0
        val kinds = searchHits(outcome).map { it.trim().substringBefore(' ') }.toSet()
        kinds shouldBe setOf("field")
    }

    @Test
    fun `kind package finds the fixture package`() {
        val outcome = JdxService.search(
            "fixtures",
            fixtureRoots(),
            SearchOptions(kind = SearchKindFilter.PACKAGE),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "package dev.jdx.fixtures"
    }

    @Test
    fun `kind module needs the JDK`() {
        val withoutJdk = JdxService.search(
            "java.base",
            fixtureRoots(),
            SearchOptions(kind = SearchKindFilter.MODULE),
        )
        withoutJdk.exitCode shouldBe 1

        val withJdk = JdxService.search(
            "java.base",
            RootsSpec(emptyList(), includeJdk = true),
            SearchOptions(kind = SearchKindFilter.MODULE),
        )
        withJdk.exitCode shouldBe 0
        textOf(withJdk) shouldContain "module java.base"
    }

    // -- search: scope filters ---------------------------------------------------

    @Test
    fun `in and package filters narrow the scope`() {
        val jarName = fixtureJar().name
        val byArtifact = JdxService.search(
            "*",
            fixtureRoots(),
            SearchOptions(inArtifact = jarName, limit = 1000),
        )
        byArtifact.exitCode shouldBe 0

        val missedArtifact = JdxService.search(
            "*",
            fixtureRoots(),
            SearchOptions(inArtifact = "nomatch-*"),
        )
        missedArtifact.exitCode shouldBe 1

        val byPackage = JdxService.search(
            "*",
            fixtureRoots(),
            SearchOptions(inPackage = "dev.jdx.fixtures", limit = 1000),
        )
        byPackage.exitCode shouldBe 0
        for (hit in searchHits(byPackage)) hit shouldContain "dev.jdx.fixtures"

        val missedPackage = JdxService.search(
            "*",
            fixtureRoots(),
            SearchOptions(inPackage = "no.such.package"),
        )
        missedPackage.exitCode shouldBe 1
    }

    // -- search: fuzzy and did-you-mean ------------------------------------------

    @Test
    fun `fuzzy retries a typo while plain search suggests`() {
        val plain = JdxService.search("Generix", fixtureRoots())
        plain.exitCode shouldBe 1
        textOf(plain) shouldContain "dev.jdx.fixtures.Generics"

        val fuzzy = JdxService.search("Generix", fixtureRoots(), SearchOptions(fuzzy = true))
        fuzzy.exitCode shouldBe 0
        textOf(fuzzy) shouldContain "dev.jdx.fixtures.Generics"
    }

    // -- search: truncation, limits, exits ----------------------------------------

    @Test
    fun `star search truncates with a limit hint`() {
        val outcome = JdxService.search("*", fixtureRoots(), SearchOptions(limit = 5))
        outcome.exitCode shouldBe 0
        val listing = (outcome as ServiceOutcome.SearchList).listing
        listing.hits.size shouldBe 5
        val truncation = listing.truncation ?: fail("expected truncation")
        textOf(outcome) shouldContain "5 of ${truncation.total} results shown (--limit ${truncation.total} to see more)"
        (truncation.total > 5) shouldBe true
    }

    @Test
    fun `negative limits are usage errors`() {
        JdxService.search("x", fixtureRoots(), SearchOptions(limit = -1)).exitCode shouldBe 3
        JdxService.resolve("x", fixtureRoots(), limit = -1).exitCode shouldBe 3
        JdxService.ls(null, fixtureRoots(), limit = -1).exitCode shouldBe 3
        JdxService.tree(null, fixtureRoots(), limit = -1).exitCode shouldBe 3
        JdxService.tree(null, fixtureRoots(), depth = -1).exitCode shouldBe 3
    }

    @Test
    fun `empty roots are exit 4`() {
        val empty = RootsSpec(emptyList(), includeJdk = false)
        JdxService.search("x", empty).exitCode shouldBe 4
        JdxService.resolve("x", empty).exitCode shouldBe 4
        JdxService.ls(null, empty).exitCode shouldBe 4
        JdxService.tree(null, empty).exitCode shouldBe 4
    }

    // -- resolve ------------------------------------------------------------------

    @Test
    fun `resolve names the type with kind and artifact`() {
        val outcome = JdxService.resolve("Generics", fixtureRoots())
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "class dev.jdx.fixtures.Generics"
        text shouldContain fixtureJar().name
    }

    @Test
    fun `resolve finds members through hash refs`() {
        val outcome = JdxService.resolve("Generics#identity", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "dev.jdx.fixtures.Generics#identity("
    }

    @Test
    fun `resolve misses with did-you-mean`() {
        val outcome = JdxService.resolve("NoSuchThingXYZ", fixtureRoots())
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "not found: NoSuchThingXYZ"
    }

    // -- ls ------------------------------------------------------------------------

    @Test
    fun `ls glob lists packages with counts`() {
        val outcome = JdxService.ls("dev.jdx.*", fixtureRoots())
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "packages matching 'dev.jdx.*'"
        text shouldContain "package dev.jdx.fixtures ("
    }

    @Test
    fun `ls exact package lists its types`() {
        val outcome = JdxService.ls("dev.jdx.fixtures", fixtureRoots(), limit = 1000)
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "types in package 'dev.jdx.fixtures'"
        text shouldContain "dev.jdx.fixtures.Generics"
    }

    @Test
    fun `ls misses with exit 1`() {
        JdxService.ls("no.such.*", fixtureRoots()).exitCode shouldBe 1
        JdxService.ls("no.such.pkg", fixtureRoots()).exitCode shouldBe 1
    }

    // -- tree -----------------------------------------------------------------------

    @Test
    fun `tree shows the fixture package forest`() {
        val outcome = JdxService.tree(fixtureJar().name, fixtureRoots(), withCounts = true)
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "package tree for '${fixtureJar().name}'"
        text shouldContain "dev.jdx.fixtures ("
    }

    @Test
    fun `tree depth zero shows top segments only`() {
        val outcome = JdxService.tree(fixtureJar().name, fixtureRoots(), depth = 0)
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "  dev"
        text shouldNotContain "dev.jdx.fixtures"
    }

    @Test
    fun `tree misses with exit 1`() {
        JdxService.tree("nomatch-*", fixtureRoots()).exitCode shouldBe 1
    }

    // -- contracts --------------------------------------------------------------------

    @Test
    fun `search twice yields identical bytes`() {
        val first = JdxService.search("fixtures", fixtureRoots(), SearchOptions(limit = 25))
        val second = JdxService.search("fixtures", fixtureRoots(), SearchOptions(limit = 25))
        textOf(first) shouldBe textOf(second)
        first.toJson("search") shouldBe second.toJson("search")
    }

    @Test
    fun `every text hit appears in json`() {
        val outcome = JdxService.search("fixtures", fixtureRoots(), SearchOptions(limit = 25))
        outcome.exitCode shouldBe 0
        val json = outcome.toJson("search")
        for (hit in (outcome as ServiceOutcome.SearchList).listing.hits) {
            json shouldContain hit.ref
            json shouldContain hit.artifact
        }
    }

    @Test
    fun `resolve type candidates equal the exact search hits`() {
        val searched = JdxService.search("dev.jdx.fixtures.Generics", fixtureRoots())
        val resolved = JdxService.resolve("dev.jdx.fixtures.Generics", fixtureRoots())
        val searchedRefs = (searched as ServiceOutcome.SearchList).listing.hits.map { it.ref }.toSet()
        val resolvedRefs = (resolved as ServiceOutcome.SearchList).listing.hits.map { it.ref }.toSet()
        searchedRefs shouldBe resolvedRefs
    }

    @Test
    fun `default limit is fifty`() {
        DEFAULT_SEARCH_LIMIT shouldBe 50
    }
}
