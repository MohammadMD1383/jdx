package dev.jdx.cli.bench

import dev.jdx.index.service.JdxService
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

/**
 * Tier 4: the benchmark smoke run (T-050). Runs via `./gradlew bench` —
 * tagged `bench` so it stays out of `test`/`check`. Asserts completion and
 * report shape only, never timing values: budgets are advisory, and a slow
 * machine must not turn the suite red.
 */
@Tag("bench")
class BenchSmokeTest {

    private fun fixtureJar(): File {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: fail("jdx.fixturesDir not set (cli build wires it; see cli/build.gradle.kts)")
        return dir.listFiles { file ->
            file.isFile && file.name.startsWith("testfixtures-") && !file.name.endsWith("-sources.jar")
        }?.singleOrNull() ?: fail("expected exactly one binary fixture jar in $dir")
    }

    @Test
    fun `one full workload iteration completes over the fixture jar`() {
        val jar = fixtureJar().absolutePath
        val roots = JdxService.RootsSpec(jarSpecs = listOf(jar), includeJdk = false)

        val report = BenchRunner.run("fixture", listOf(jar), roots, JdxService, iterations = 1)

        report.iterations shouldBe 1
        report.rows.map { it.name } shouldBe BenchRunner.CASES.map { it.name }
        (report.classCount > 0) shouldBe true
    }
}
