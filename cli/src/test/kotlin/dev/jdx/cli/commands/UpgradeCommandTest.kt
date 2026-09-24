package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.jdx.cli.JdxCli
import dev.jdx.cli.service.UpgradeService
import dev.jdx.index.maven.MavenFetch
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * `jdx upgrade` (tier 1): text/JSON renderings and exit codes over an
 * injected [UpgradeService] with a canned fetcher — no test touches the
 * network. A throwing terminator keeps failure paths off the test JVM.
 */
class UpgradeCommandTest {

    /** Thrown by the test terminator instead of killing the test JVM. */
    class UpgradeExit(val code: Int) : RuntimeException()

    private fun serviceFor(
        current: String,
        canned: Map<String, ByteArray>,
        installRoot: java.nio.file.Path? = null,
    ): UpgradeService = UpgradeService(
        currentVersion = current,
        installRoot = installRoot,
        fetcher = MavenFetch.Fetcher { url -> canned[url] },
    )

    private fun command(service: UpgradeService): UpgradeCommand =
        UpgradeCommand(service, terminate = { throw UpgradeExit(it) })

    private fun latestCanned(tag: String, repo: String = "o/n"): Map<String, ByteArray> =
        mapOf(
            "https://api.github.com/repos/$repo/releases/latest" to
                """{"tag_name":"$tag"}""".toByteArray(),
        )

    @Test
    fun `--check on the latest says up to date`() {
        val output = captureStdout {
            JdxCli().subcommands(command(serviceFor("v1.1.0", latestCanned("v1.1.0"))))
                .parse(listOf("upgrade", "--check", "--repo", "o/n"))
        }

        output.trim() shouldBe "jdx v1.1.0 is up to date (latest: v1.1.0)"
    }

    @Test
    fun `--check behind latest points at the update`() {
        val output = captureStdout {
            JdxCli().subcommands(command(serviceFor("v1.0.0", latestCanned("v1.1.0"))))
                .parse(listOf("upgrade", "--check", "--repo", "o/n"))
        }

        output.trim() shouldContain "update available: jdx v1.0.0 -> v1.1.0"
    }

    @Test
    fun `--json carries the status payload in the envelope`() {
        val output = captureStdout {
            JdxCli().subcommands(command(serviceFor("v1.0.0", latestCanned("v1.1.0"))))
                .parse(listOf("upgrade", "--check", "--repo", "o/n", "--json"))
        }

        val parsed = Json.parseToJsonElement(output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "upgrade"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        val result = parsed["result"]?.jsonObject ?: error("upgrade json has no result")
        result["status"]?.jsonPrimitive?.content shouldBe "available"
        result["current"]?.jsonPrimitive?.content shouldBe "v1.0.0"
        result["latest"]?.jsonPrimitive?.content shouldBe "v1.1.0"
    }

    @Test
    fun `an unknown tag exits 1`() {
        val output = captureStdout {
            val thrown = shouldThrow<UpgradeExit> {
                JdxCli().subcommands(command(serviceFor("v1.0.0", emptyMap())))
                    .parse(listOf("upgrade", "--version", "v9.9.9", "--repo", "o/n"))
            }
            thrown.code shouldBe 1
        }

        output.trim() shouldContain "no release 'v9.9.9'"
    }

    @Test
    fun `upgrading a non-release install exits 3`() {
        val output = captureStdout {
            val thrown = shouldThrow<UpgradeExit> {
                JdxCli().subcommands(command(serviceFor("v1.0.0", latestCanned("v1.1.0"))))
                    .parse(listOf("upgrade", "--repo", "o/n"))
            }
            thrown.code shouldBe 3
        }

        output.trim() shouldContain "not a release install"
    }

    @Test
    fun `an unreachable api exits 5`() {
        val output = captureStdout {
            val thrown = shouldThrow<UpgradeExit> {
                JdxCli().subcommands(command(serviceFor("v1.0.0", emptyMap())))
                    .parse(listOf("upgrade", "--check", "--repo", "o/n"))
            }
            thrown.code shouldBe 5
        }

        output.trim() shouldContain "upgrade failed"
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
