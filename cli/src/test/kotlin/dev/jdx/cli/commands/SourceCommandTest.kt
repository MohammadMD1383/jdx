package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.render.buildSourceBlock
import dev.jdx.decompile.DecompilerId
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Test

/**
 * In-process tests for `jdx source` (T-023).
 *
 * No disk, no jars: the service query and the process exit are both injected, so every
 * path — including the non-zero exits — runs in tier 1. Real-artifact behaviour (the
 * fixture jar, goldens) is tier 2 in `SourceCommandsServiceTest` and `SourceGoldenTest`.
 */
class SourceCommandTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }

    // Hermetic roots (T-066/T-070): an empty in-memory store and an empty environment,
    // so the contributor's ambient `~/.config/jdx/active-workspace` (e.g. `fx`) and
    // `JDX_WORKSPACE` can never re-root these assertions.
    private val noEnv: (String) -> String? = { null }

    private fun sourceBlock() = JdxService.ServiceOutcome.Source(
        buildSourceBlock(
            canonicalRef = "com.example.Point",
            declaringType = "com.example.Point",
            file = "com/example/Point.java",
            fileLines = listOf("package com.example;", "public class Point {", "}"),
            startLine = 1,
            endLine = 3,
            provenance = listOf(Provenance(artifact = "app-sources.jar", origin = Origin.SOURCES)),
        ),
    )

    private data class Run(val output: String, val exit: Int, val options: JdxService.SourceOptions?)

    private fun run(args: List<String>): Run {
        var seen: JdxService.SourceOptions? = null
        val query: SourceQuery = { _, _, options ->
            seen = options
            sourceBlock()
        }
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            SourceCommand(
                query = query,
                terminate = noExit,
                store = InMemoryWorkspaceStore(),
                getenv = noEnv,
                discover = noDiscovery,
            ).parse(listOf("--jars", "app.jar") + args)
            0
        } catch (e: TestExit) {
            e.code
        } finally {
            System.setOut(original)
        }
        return Run(buffer.toString(Charsets.UTF_8), exit, seen)
    }

    @Test
    fun `source prints the block text and exits 0`() {
        val run = run(listOf("com.example.Point"))
        run.exit shouldBe 0
        run.output shouldContain "com.example.Point"
        run.output shouldContain "package com.example;"
    }

    @Test
    fun `lines around context line-numbers and max-lines reach the service`() {
        val run = run(
            listOf(
                "com.example.Point", "--lines", "100:180", "--context", "0",
                "--line-numbers", "--max-lines", "10",
            ),
        )
        run.exit shouldBe 0
        run.options shouldBe JdxService.SourceOptions(
            lines = 100 to 180,
            aroundRef = null,
            contextLines = 0,
            lineNumbers = true,
            maxLines = 10,
        )
        val around = run(listOf("com.example.Point", "--around", "com.example.Point#getX()", "--context", "3"))
        around.exit shouldBe 0
        around.options?.aroundRef shouldBe "com.example.Point#getX()"
        around.options?.contextLines shouldBe 3
    }

    @Test
    fun `bad lines shapes are usage errors`() {
        for (shape in listOf("abc", "1:2:3", "5:2", "0:5", "10:", ":10", "")) {
            val run = run(listOf("com.example.Point", "--lines", shape))
            run.exit shouldBe 3
            run.output shouldContain "--lines"
        }
    }

    @Test
    fun `lines and around are mutually exclusive`() {
        val run = run(listOf("com.example.Point", "--lines", "1:2", "--around", "com.example.Point#getX()"))
        run.exit shouldBe 3
        run.output shouldContain "mutually exclusive"
    }

    @Test
    fun `context without around is a usage error`() {
        val run = run(listOf("com.example.Point", "--context", "3"))
        run.exit shouldBe 3
        run.output shouldContain "--around"
    }

    @Test
    fun `both engines reach the service`() {
        val forced = run(listOf("com.example.Point", "--engine", "vineflower"))
        forced.exit shouldBe 0
        forced.options?.engine shouldBe DecompilerId.VINEFLOWER
        val raw = run(listOf("com.example.Point", "--engine", "javap"))
        raw.exit shouldBe 0
        raw.options?.engine shouldBe DecompilerId.JAVAP
    }

    @Test
    fun `negative context and max-lines are usage errors`() {
        run(listOf("com.example.Point", "--context", "-1", "--around", "com.example.Point#getX()")).exit shouldBe 3
        run(listOf("com.example.Point", "--max-lines", "-1")).exit shouldBe 3
    }

    @Test
    fun `json flag renders the envelope`() {
        val run = run(listOf("com.example.Point", "--json"))
        run.exit shouldBe 0
        run.output shouldContain "\"command\":\"source\""
        run.output shouldContain "\"ok\":true"
    }

    @Test
    fun `validateSourceFlags accepts the defaults`() {
        validateSourceFlags(lines = null, around = null, context = 0, maxLines = 200, engine = null) shouldBe null
    }

    @Test
    fun `parseLinesWindow tolerates whitespace and rejects nonsense`() {
        parseLinesWindow("100:180") shouldBe (100 to 180)
        parseLinesWindow("  100 : 180  ") shouldBe (100 to 180)
        parseLinesWindow("abc") shouldBe null
        parseLinesWindow("5:2") shouldBe null
        parseLinesWindow("0:5") shouldBe null
    }
}
