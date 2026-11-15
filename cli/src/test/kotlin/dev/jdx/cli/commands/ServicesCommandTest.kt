package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.core.render.ErrorResult
import dev.jdx.core.render.ServiceHit
import dev.jdx.core.render.buildServiceListing
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Test

/**
 * In-process tests for `jdx services` (issue #25). No disk, no jars: the
 * service query and process exit are injected.
 */
class ServicesCommandTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }
    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }
    private val noEnv: (String) -> String? = { null }

    private fun hitOutcome(pattern: String?) = JdxService.ServiceOutcome.ServiceList(
        buildServiceListing(
            pattern.orEmpty(),
            listOf(ServiceHit("com.example.Spi", "com.example.Alpha", "lib.jar")),
        ),
    )

    private data class Run(val output: String, val exit: Int, val options: JdxService.ServiceOptions?)

    private fun run(args: List<String>, query: ServicesQuery? = null): Run {
        var seen: JdxService.ServiceOptions? = null
        val injected: ServicesQuery = query ?: { pattern, _, options ->
            seen = options
            hitOutcome(pattern)
        }
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            ServicesCommand(
                query = injected,
                terminate = noExit,
                store = InMemoryWorkspaceStore(),
                getenv = noEnv,
                discover = noDiscovery,
            ).parse(listOf("--jars", "lib.jar", "--no-daemon") + args)
            0
        } catch (e: TestExit) {
            e.code
        } finally {
            System.setOut(original)
        }
        return Run(buffer.toString(Charsets.UTF_8), exit, seen)
    }

    @Test
    fun `prints hits and exits 0`() {
        val result = run(listOf("Spi", "--ignore-case", "--limit", "10"))
        result.exit shouldBe 0
        result.output shouldContain "services matching 'Spi'"
        result.output shouldContain "provider com.example.Alpha"
        result.options?.limit shouldBe 10
        result.options?.ignoreCase shouldBe true
    }

    @Test
    fun `omitted pattern lists every registration`() {
        val result = run(emptyList())
        result.exit shouldBe 0
        result.output shouldContain "services"
        result.output shouldContain "provider com.example.Alpha"
    }

    @Test
    fun `miss exits 1`() {
        val result = run(listOf("missing")) { pattern, _, _ ->
            JdxService.ServiceOutcome.Failure(
                ErrorResult.notFound(pattern.orEmpty(), detail = "no services matching '$pattern'"),
            )
        }
        result.exit shouldBe 1
    }

    @Test
    fun `negative limit exits 3 without querying`() {
        var queried = false
        val result = run(listOf("x", "--limit", "-1")) { pattern, _, _ ->
            queried = true
            hitOutcome(pattern)
        }
        result.exit shouldBe 3
        queried shouldBe false
    }
}
