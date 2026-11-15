package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.core.render.ManifestHit
import dev.jdx.core.render.buildManifestListing
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * In-process tests for `jdx manifest` (issue #25). No disk, no jars: the
 * query is injected so flag plumbing and exit codes stay hermetic.
 */
class ManifestCommandTest {

    private fun hitOutcome(pattern: String?) = JdxService.ServiceOutcome.ManifestList(
        buildManifestListing(
            pattern.orEmpty(),
            listOf(
                ManifestHit(
                    name = "Main-Class",
                    value = "com.example.Main",
                    artifact = "app.jar",
                ),
            ),
        ),
    )

    private data class Run(val output: String, val exit: Int, val options: JdxService.ManifestOptions?)

    private fun run(vararg args: String, outcome: JdxService.ServiceOutcome = hitOutcome(null)): Run {
        var seen: JdxService.ManifestOptions? = null
        var exit = 0
        val buf = ByteArrayOutputStream()
        val previous = System.out
        System.setOut(PrintStream(buf))
        try {
            ManifestCommand(
                query = { pattern, _, options ->
                    seen = options
                    when (outcome) {
                        is JdxService.ServiceOutcome.ManifestList ->
                            if (pattern.isNullOrBlank()) outcome
                            else hitOutcome(pattern)
                        else -> outcome
                    }
                },
                terminate = { code ->
                    exit = code
                    throw Terminate(code)
                },
                store = InMemoryWorkspaceStore(),
            ).parse(args.toList())
        } catch (_: Terminate) {
            // expected
        } finally {
            System.setOut(previous)
        }
        return Run(buf.toString(Charsets.UTF_8), exit, seen)
    }

    private class Terminate(val code: Int) : RuntimeException()

    @Test
    fun `forwards pattern and default options`() {
        val run = run("Main-Class")
        run.exit shouldBe 0
        run.output shouldContain "Main-Class: com.example.Main"
        run.options!!.limit shouldBe 50
        run.options.regex shouldBe false
    }

    @Test
    fun `forwards regex ignore-case in exclude and limit`() {
        val run = run(
            "Main",
            "--regex",
            "--ignore-case",
            "--in", "app.jar",
            "--exclude", "other.jar",
            "--limit", "3",
            "--no-jdk",
        )
        run.exit shouldBe 0
        run.options!!.regex shouldBe true
        run.options.ignoreCase shouldBe true
        run.options.inArtifact shouldBe "app.jar"
        run.options.exclude shouldBe "other.jar"
        run.options.limit shouldBe 3
    }

    @Test
    fun `negative limit exits 3 without calling the query`() {
        var called = false
        var exit = 0
        val buf = ByteArrayOutputStream()
        val previous = System.out
        System.setOut(PrintStream(buf))
        try {
            ManifestCommand(
                query = { _, _, _ ->
                    called = true
                    hitOutcome(null)
                },
                terminate = { code ->
                    exit = code
                    throw Terminate(code)
                },
                store = InMemoryWorkspaceStore(),
            ).parse(listOf("--limit", "-1", "--no-jdk"))
        } catch (_: Terminate) {
            // expected
        } finally {
            System.setOut(previous)
        }
        called shouldBe false
        exit shouldBe 3
        buf.toString(Charsets.UTF_8) shouldContain "--limit must be >= 0"
    }
}
