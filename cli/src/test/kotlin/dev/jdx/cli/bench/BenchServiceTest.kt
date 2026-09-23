package dev.jdx.cli.bench

import com.github.ajalt.clikt.core.parse
import dev.jdx.cli.commands.BenchCommand
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

/**
 * Tier 2: the real `jdx bench` workload over the fixture jar (T-050).
 * Assertions are structural (rows present, timings non-negative, JSON
 * parses) — never on timing values, so machine variance cannot turn these
 * red. Timing budgets are advisory by design (see [BenchRunner]).
 */
@Tag("tier2")
class BenchServiceTest {

    private class TestExit(val code: Int) : RuntimeException()

    private fun fixtureJar(): File {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: fail("jdx.fixturesDir not set (cli build wires it; see cli/build.gradle.kts)")
        return dir.listFiles { file ->
            file.isFile && file.name.startsWith("testfixtures-") && !file.name.endsWith("-sources.jar")
        }?.singleOrNull() ?: fail("expected exactly one binary fixture jar in $dir")
    }

    private data class Run(val lines: List<String>, val exit: Int)

    private fun run(args: List<String>): Run {
        val printed = mutableListOf<String>()
        val exit = try {
            BenchCommand(
                terminate = { throw TestExit(it) },
                store = InMemoryWorkspaceStore(),
                getenv = { null },
                discover = { _, _, _, _ -> null },
                minecraftProbe = { null },
                printer = printed::add,
            ).parse(args)
            0
        } catch (e: TestExit) {
            e.code
        }
        return Run(printed, exit)
    }

    @Test
    fun `the runner measures every case over the fixture jar`() {
        val jar = fixtureJar().absolutePath
        val roots = JdxService.RootsSpec(jarSpecs = listOf(jar), includeJdk = false)

        val report = BenchRunner.run("fixture", listOf(jar), roots, JdxService, iterations = 1)

        report.classCount shouldBe BenchRunner.listClassNames(listOf(jar)).size
        (report.classCount > 0) shouldBe true
        report.rows.map { it.name } shouldBe BenchRunner.CASES.map { it.name }
        for (row in report.rows) {
            (row.medianMs >= 0) shouldBe true
            row.withinTarget shouldBe (row.medianMs <= row.targetMs)
        }
    }

    @Test
    fun `bench over the fixture jar exits 0 with the timing table`() {
        val run = run(listOf("--jars", fixtureJar().absolutePath, "--no-jdk", "--iterations", "1"))

        run.exit shouldBe 0
        run.lines.size shouldBe 1
        run.lines.single() shouldContain "jdx bench testfixtures-"
        run.lines.single() shouldContain "within target"
        for (case in BenchRunner.CASES) {
            run.lines.single() shouldContain case.name
        }
    }

    @Test
    fun `bench json over the fixture jar parses with every row`() {
        val run = run(listOf("--jars", fixtureJar().absolutePath, "--no-jdk", "--iterations", "1", "--json"))

        run.exit shouldBe 0
        val parsed = Json.parseToJsonElement(run.lines.single()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "bench"
        parsed["result"]?.jsonObject?.get("rows")?.jsonArray?.size shouldBe BenchRunner.CASES.size
    }
}
