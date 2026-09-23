package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.jdx.cli.JdxCli
import dev.jdx.index.kotlin.KotlinSidecarOutcome
import dev.jdx.index.kotlin.KotlinSidecarReport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `jdx kotlin install` (T-080, tier 1): flag validation, exit codes, and the
 * text/JSON renderings over an injected fetch — no test touches the network.
 */
class KotlinInstallCommandTest {

    private fun okFetch(report: KotlinSidecarReport): KotlinSidecarFetchFn =
        { _, _, _, _ -> KotlinSidecarOutcome.Ok(report) }

    private fun command(
        fetch: KotlinSidecarFetchFn,
        home: Path,
        terminate: (Int) -> Nothing = { throw KotlinExit(it) },
    ): KotlinInstallCommand = KotlinInstallCommand(fetch, home, terminate)

    /** The `kotlin` group with a throwing terminator, so failures never kill the test JVM. */
    private fun testGroup(fetch: KotlinSidecarFetchFn, home: Path): KotlinCommand =
        kotlinGroup(fetch, home, terminate = { throw KotlinExit(it) })

    @Test
    fun `a successful install prints every jar and exits 0`(@TempDir home: Path) {
        val report = KotlinSidecarReport(
            installed = listOf("a.jar", "b.jar"),
            alreadyPresent = listOf("c.jar"),
        )
        val output = captureStdout {
            JdxCli().subcommands(testGroup(okFetch(report), home)).parse(listOf("kotlin", "install"))
        }

        output shouldContain "installed: a.jar"
        output shouldContain "installed: b.jar"
        output shouldContain "present: c.jar"
        output shouldContain "~/.cache/jdx/kotlin"
    }

    @Test
    fun `an already-complete sidecar says so`(@TempDir home: Path) {
        val report = KotlinSidecarReport(emptyList(), listOf("a.jar"))
        val output = captureStdout {
            JdxCli().subcommands(testGroup(okFetch(report), home)).parse(listOf("kotlin", "install"))
        }

        output shouldContain "already complete"
    }

    @Test
    fun `a failed fetch exits 5 naming the problem`(@TempDir home: Path) {
        val fetch: KotlinSidecarFetchFn = { _, _, _, _ ->
            KotlinSidecarOutcome.Failed("cannot fetch Kotlin sidecar artifact 'x:y:1'")
        }
        val output = captureStdout {
            val thrown = shouldThrow<KotlinExit> {
                JdxCli().subcommands(testGroup(fetch, home)).parse(listOf("kotlin", "install"))
            }
            thrown.code shouldBe 5
        }

        output shouldContain "cannot fetch Kotlin sidecar artifact"
    }

    @Test
    fun `an invalid repo exits 3 without fetching`(@TempDir home: Path) {
        var calls = 0
        val fetch: KotlinSidecarFetchFn = { _, _, _, _ ->
            calls++
            KotlinSidecarOutcome.Ok(KotlinSidecarReport(emptyList(), emptyList()))
        }
        val output = captureStdout {
            val thrown = shouldThrow<KotlinExit> {
                JdxCli().subcommands(testGroup(fetch, home))
                    .parse(listOf("kotlin", "install", "--repo", "not a url"))
            }
            thrown.code shouldBe 3
        }

        output shouldContain "invalid --repo"
        calls shouldBe 0
    }

    @Test
    fun `json install carries the report in the envelope`(@TempDir home: Path) {
        val report = KotlinSidecarReport(listOf("a.jar"), listOf("c.jar"))
        val output = captureStdout {
            JdxCli().subcommands(testGroup(okFetch(report), home))
                .parse(listOf("kotlin", "install", "--json"))
        }

        val parsed = Json.parseToJsonElement(output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "kotlin install"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        parsed["result"]?.jsonObject?.get("installed").toString() shouldContain "a.jar"
    }

    @Test
    fun `json failure rides the envelope with ok false`(@TempDir home: Path) {
        val fetch: KotlinSidecarFetchFn = { _, _, _, _ ->
            KotlinSidecarOutcome.Failed("boom")
        }
        val output = captureStdout {
            shouldThrow<KotlinExit> {
                JdxCli().subcommands(testGroup(fetch, home))
                    .parse(listOf("kotlin", "install", "--json"))
            }
        }

        val parsed = Json.parseToJsonElement(output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "kotlin install"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "false"
    }

    @Test
    fun `force reaches the fetch`(@TempDir home: Path) {
        var seenForce = false
        val fetch: KotlinSidecarFetchFn = { _, _, _, force ->
            seenForce = force
            KotlinSidecarOutcome.Ok(KotlinSidecarReport(emptyList(), emptyList()))
        }
        captureStdout {
            JdxCli().subcommands(testGroup(fetch, home)).parse(listOf("kotlin", "install", "--force"))
        }

        seenForce shouldBe true
    }

    // -- generating family: rendering determinism + hostile never-throws --

    @Test
    fun `rendering is deterministic over generated reports`(): Unit = runBlocking {
        checkAll(200, Arb.list(Arb.string(), 0..5), Arb.list(Arb.string(), 0..5)) { installed, present ->
            renderInstallText(installed, present) shouldBe renderInstallText(installed, present)
        }
    }

    @Test
    fun `rendering never throws on hostile names`(): Unit = runBlocking {
        checkAll(200, Arb.list(Arb.string(), 0..5), Arb.list(Arb.string(), 0..5)) { installed, present ->
            try {
                renderInstallText(installed, present)
            } catch (e: Exception) {
                throw AssertionError("render threw: $e")
            }
        }
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }
}
