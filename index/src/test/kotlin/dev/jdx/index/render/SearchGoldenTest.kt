package dev.jdx.index.render

import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.SearchKindFilter
import dev.jdx.index.service.JdxService.SearchOptions
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for the search/ls/tree/resolve renderers over the fixture
 * corpus (T-017): one text and one JSON file per query, pinned under
 * `src/test/resources/golden/search/`.
 *
 * Hermeticity: the real fixture jar name (`testfixtures-<version>.jar`) is
 * rewritten to `fixture-corpus.jar` before comparison, so a version bump
 * cannot turn these red — the same fixed-label rule the T-010 goldens follow.
 * The JDK is excluded (`--no-jdk` roots), so JDK upgrades are invisible here.
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read
 * the diff before committing it (TESTING.md §14).
 */
@Tag("tier2")
class SearchGoldenTest {

    private fun fixtureJar(): File {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: fail("jdx.fixturesDir not set (index build wires it; see index/build.gradle.kts)")
        return dir.listFiles { file ->
            file.isFile && file.name.startsWith("testfixtures-") && !file.name.endsWith("-sources.jar")
        }?.singleOrNull() ?: fail("expected exactly one binary fixture jar in $dir")
    }

    private fun roots(): RootsSpec =
        RootsSpec(jarSpecs = listOf(fixtureJar().absolutePath), includeJdk = false)

    private fun sanitize(output: String): String = output.replace(fixtureJar().name, "fixture-corpus.jar")

    private fun textOf(outcome: ServiceOutcome, command: String): String =
        sanitize(outcome.renderText(false))

    private fun jsonOf(outcome: ServiceOutcome, command: String): String =
        sanitize(outcome.toJson(command))

    private fun checkOk(outcome: ServiceOutcome, label: String): ServiceOutcome {
        if (outcome.exitCode != 0) fail("$label: exit ${outcome.exitCode}: ${textOf(outcome, label)}")
        return outcome
    }

    @Test
    fun `text and json goldens cover the search family`() {
        val jarName = fixtureJar().name
        val queries: List<Triple<String, String, ServiceOutcome>> = listOf(
            Triple("search-generics", "search", checkOk(JdxService.search("Generics", roots()), "search")),
            Triple("search-humps", "search", checkOk(JdxService.search("CovOver", roots()), "search")),
            Triple(
                "search-regex-record",
                "search",
                checkOk(
                    JdxService.search(".*Record", roots(), SearchOptions(kind = SearchKindFilter.RECORD, regex = true)),
                    "search",
                ),
            ),
            Triple(
                "search-method",
                "search",
                checkOk(
                    JdxService.search("identity", roots(), SearchOptions(kind = SearchKindFilter.METHOD)),
                    "search",
                ),
            ),
            Triple(
                "search-package",
                "search",
                checkOk(
                    JdxService.search("fixtures", roots(), SearchOptions(kind = SearchKindFilter.PACKAGE)),
                    "search",
                ),
            ),
            Triple("resolve-type", "resolve", checkOk(JdxService.resolve("Generics", roots()), "resolve")),
            Triple(
                "resolve-member",
                "resolve",
                checkOk(JdxService.resolve("Generics#identity", roots()), "resolve"),
            ),
            Triple("ls-packages", "ls", checkOk(JdxService.ls("dev.jdx.*", roots()), "ls")),
            Triple("ls-types", "ls", checkOk(JdxService.ls("dev.jdx.fixtures", roots()), "ls")),
            Triple(
                "tree",
                "tree",
                checkOk(JdxService.tree(jarName, roots(), withCounts = true), "tree"),
            ),
        )
        val contents = buildMap {
            for ((name, command, outcome) in queries) {
                put("$name.txt", textOf(outcome, command))
                put("$name.json", jsonOf(outcome, command))
            }
        }
        GoldenFiles.verifyAll(File("src/test/resources/golden/search"), contents)
    }
}
