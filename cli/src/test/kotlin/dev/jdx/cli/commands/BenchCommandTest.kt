package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.cli.bench.BenchRunner
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Tier 1: `jdx bench` flag validation and envelope shape (T-050). No IO —
 * the workload is injected, roots resolve against an isolated store with no
 * environment and no project discovery.
 */
class BenchCommandTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }
    private val noEnv: (String) -> String? = { null }
    private val noProbe: () -> java.nio.file.Path? = { null }

    private data class Run(val lines: List<String>, val exit: Int)

    private fun fixedReport(): BenchRunner.Report = BenchRunner.Report(
        label = "x.jar",
        classCount = 4,
        iterations = 1,
        rows = listOf(BenchRunner.Row("show", 250, 3, true)),
    )

    private fun run(
        args: List<String>,
        bench: (String, List<String>, JdxService.RootsSpec, Int) -> BenchRunner.Report = { label, _, _, iterations ->
            fixedReport().copy(label = label, iterations = iterations)
        },
    ): Run {
        val printed = mutableListOf<String>()
        val exit = try {
            BenchCommand(
                terminate = { throw TestExit(it) },
                store = InMemoryWorkspaceStore(),
                getenv = noEnv,
                discover = noDiscovery,
                minecraftProbe = noProbe,
                bench = bench,
                printer = printed::add,
            ).parse(args)
            0
        } catch (e: TestExit) {
            e.code
        }
        return Run(printed, exit)
    }

    @Test
    fun `iterations below one exits 3`() {
        val run = run(listOf("--jars", "/fake/x.jar", "--no-jdk", "--iterations", "0"))

        run.exit shouldBe 3
        run.lines.single() shouldContain "--iterations must be >= 1"
    }

    @Test
    fun `iterations below one exits 3 in json too`() {
        val run = run(listOf("--jars", "/fake/x.jar", "--no-jdk", "--iterations", "0", "--json"))

        run.exit shouldBe 3
        val parsed = Json.parseToJsonElement(run.lines.single()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "bench"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "false"
    }

    @Test
    fun `missed probe with no roots exits 4 naming jars`() {
        val run = run(listOf("--no-jdk"))

        run.exit shouldBe 4
        run.lines.single() shouldContain "--jars"
    }

    @Test
    fun `success prints the timing table and exits 0`() {
        val seen = mutableListOf<Int>()
        val run = run(
            listOf("--jars", "/fake/x.jar", "--no-jdk", "--iterations", "2"),
            bench = { label, specs, _, iterations ->
                specs shouldBe listOf("/fake/x.jar")
                seen.add(iterations)
                fixedReport().copy(label = label, iterations = iterations)
            },
        )

        run.exit shouldBe 0
        seen shouldBe listOf(2)
        run.lines.single() shouldContain "jdx bench x.jar (4 classes, 2 iterations)"
    }

    @Test
    fun `success with json prints the bench envelope`() {
        val run = run(listOf("--jars", "/fake/x.jar", "--no-jdk", "--json"))

        run.exit shouldBe 0
        val parsed = Json.parseToJsonElement(run.lines.single()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "bench"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        parsed["result"]?.jsonObject?.get("label")?.jsonPrimitive?.content shouldBe "x.jar"
    }

    @Test
    fun `an unreadable artifact exits 5`() {
        val run = run(
            listOf("--jars", "/fake/x.jar", "--no-jdk"),
            bench = { _, _, _, _ -> throw dev.jdx.index.artifact.ArtifactReadException("artifact read error: boom") },
        )

        run.exit shouldBe 5
        run.lines.single() shouldContain "boom"
    }

    @Test
    fun `an empty jar exits 1`() {
        val run = run(
            listOf("--jars", "/fake/x.jar", "--no-jdk"),
            bench = { _, _, _, _ -> throw IllegalArgumentException("no classes to benchmark in x.jar") },
        )

        run.exit shouldBe 1
        run.lines.single() shouldContain "no classes"
    }
}
