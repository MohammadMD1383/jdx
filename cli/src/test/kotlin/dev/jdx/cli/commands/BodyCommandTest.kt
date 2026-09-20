package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.render.buildBodyBlock
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Test

/**
 * In-process tests for `jdx body` (T-022).
 *
 * No disk, no jars: the service query and the process exit are both injected, so every
 * path — including the non-zero exits — runs in tier 1. Real-artifact behaviour (the
 * fixture jar, goldens) is tier 2 in `BodyCommandsServiceTest` and `BodyGoldenTest`.
 */
class BodyCommandTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }

    // Hermetic roots (T-066/T-070): an empty in-memory store and an empty environment,
    // so the contributor's ambient `~/.config/jdx/active-workspace` (e.g. `fx`) and
    // `JDX_WORKSPACE` can never re-root these assertions.
    private val noEnv: (String) -> String? = { null }

    private fun bodyBlock() = JdxService.ServiceOutcome.Body(
        buildBodyBlock(
            canonicalRef = "com.example.Point#getX()",
            declaringType = "com.example.Point",
            file = "com/example/Point.java",
            fileLines = listOf("public int getX() {", "return x;", "}"),
            startLine = 1,
            endLine = 3,
            provenance = listOf(Provenance(artifact = "app-sources.jar", origin = Origin.SOURCES)),
        ),
    )

    private data class Run(val output: String, val exit: Int, val options: JdxService.BodyOptions?)

    private fun run(args: List<String>): Run {
        var seen: JdxService.BodyOptions? = null
        val query: BodyQuery = { _, _, options ->
            seen = options
            bodyBlock()
        }
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            BodyCommand(
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
    fun `body prints the block text and exits 0`() {
        val run = run(listOf("com.example.Point#getX()"))
        run.exit shouldBe 0
        run.output shouldContain "com.example.Point#getX()"
        run.output shouldContain "return x;"
    }

    @Test
    fun `context line-numbers and max-lines reach the service`() {
        val run = run(listOf("com.example.Point#getX()", "--context", "3", "--line-numbers", "--max-lines", "10"))
        run.exit shouldBe 0
        run.options shouldBe JdxService.BodyOptions(contextLines = 3, lineNumbers = true, maxLines = 10)
    }

    @Test
    fun `engine is rejected naming the decompiler tasks`() {
        for (engine in listOf("vineflower", "javap")) {
            val run = run(listOf("com.example.Point#getX()", "--engine", engine))
            run.exit shouldBe 3
            run.output shouldContain "T-026"
            run.output shouldContain "T-027"
        }
    }

    @Test
    fun `with-doc is rejected naming T-072`() {
        val run = run(listOf("com.example.Point#getX()", "--with-doc"))
        run.exit shouldBe 3
        run.output shouldContain "T-072"
    }

    @Test
    fun `with-signature reaches the service`() {
        val run = run(listOf("com.example.Point#getX()", "--with-signature"))
        run.exit shouldBe 0
        run.options shouldBe JdxService.BodyOptions(withSignature = true)
    }

    @Test
    fun `negative context and max-lines are usage errors`() {
        run(listOf("com.example.Point#getX()", "--context", "-1")).exit shouldBe 3
        run(listOf("com.example.Point#getX()", "--max-lines", "-1")).exit shouldBe 3
    }

    @Test
    fun `json flag renders the envelope`() {
        val run = run(listOf("com.example.Point#getX()", "--json"))
        run.exit shouldBe 0
        run.output shouldContain "\"command\":\"body\""
        run.output shouldContain "\"ok\":true"
    }

    @Test
    fun `validateBodyFlags accepts the defaults`() {
        validateBodyFlags(context = 0, maxLines = 200, engine = null, withDoc = false) shouldBe null
    }
}
