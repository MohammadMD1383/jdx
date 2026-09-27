package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.jdx.cli.JdxCli
import dev.jdx.cli.service.SetupService
import dev.jdx.cli.service.setupExitCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * `jdx setup` (tier 1): flag validation, text/JSON renderings, and exit codes
 * over a service factory pinned to fake homes — no test touches the real home
 * directory. A throwing terminator keeps failure paths off the test JVM.
 */
class SetupCommandTest {

    /** Thrown by the test terminator instead of killing the test JVM. */
    class SetupExit(val code: Int) : RuntimeException()

    private fun commandFor(
        home: Path,
        project: Path,
        version: SetupService.OpencodeVersionInfo = SetupService.OpencodeVersionInfo(
            SetupService.OpencodeVersion.ABSENT,
        ),
    ): SetupCommand = SetupCommand(
        serviceFactory = { _, _ -> SetupService(home, project) },
        terminate = { throw SetupExit(it) },
        versionProbe = { version },
    )

    private fun kiloCommandFor(home: Path, project: Path): SetupCommand = SetupCommand(
        serviceFactory = { _, _ -> SetupService(home, project) },
        terminate = { throw SetupExit(it) },
        // Kilo wiring must never consult `opencode --version`: a throwing probe pins that.
        versionProbe = { throw AssertionError("kilo setup must not probe the opencode binary") },
    )

    private fun fakeDirs(root: Path): Pair<Path, Path> {
        val home = root.resolve("home").also { Files.createDirectories(it) }
        val project = root.resolve("project").also { Files.createDirectories(it) }
        return home to project
    }

    @Test
    fun `install writes the entry and says what to do next`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "opencode", "--scope", "project"))
        }

        output.trim() shouldContain "opencode project setup installed"
        output.trim() shouldContain "v1+v2 entries"
        output.trim() shouldContain "restart opencode"
        output.trim() shouldContain "opencode not found on PATH"
        SetupService.isInstalledAt(project.resolve("opencode.json")) shouldBe true
    }

    @Test
    fun `install names the detected opencode line`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val v2 = SetupService.OpencodeVersionInfo(
            SetupService.OpencodeVersion.V2,
            "opencode v0.0.0-next-17403",
            "opencode",
        )
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project, v2))
                .parse(listOf("setup", "--agent", "opencode", "--scope", "project"))
        }

        output.trim() shouldContain "opencode project setup installed"
        output.trim() shouldContain "detected: opencode v2 (opencode v0.0.0-next-17403)"
    }

    @Test
    fun `a second install is a no-op reporting already installed`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val args = listOf("setup", "--agent", "opencode", "--scope", "project")
        captureStdout { JdxCli().subcommands(commandFor(home, project)).parse(args) }

        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project)).parse(args)
        }

        output.trim() shouldContain "already installed"
    }

    @Test
    fun `system scope writes under the fake home`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--scope", "system"))
        }

        SetupService.isInstalledAt(home.resolve(".config/opencode/opencode.json")) shouldBe true
    }

    @Test
    fun `check exits 1 when absent and 0 once installed`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val check = listOf("setup", "--scope", "project", "--check")

        val absent = shouldThrow<SetupExit> {
            captureStdout { JdxCli().subcommands(commandFor(home, project)).parse(check) }
        }
        absent.code shouldBe 1

        captureStdout {
            JdxCli().subcommands(commandFor(home, project)).parse(listOf("setup", "--scope", "project"))
        }
        captureStdout { JdxCli().subcommands(commandFor(home, project)).parse(check) }
    }

    @Test
    fun `check writes nothing`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)

        try {
            captureStdout {
                JdxCli().subcommands(commandFor(home, project))
                    .parse(listOf("setup", "--scope", "project", "--check"))
            }
        } catch (_: SetupExit) {
        }

        Files.exists(project.resolve("opencode.json")) shouldBe false
    }

    @Test
    fun `remove uninstalls cleanly`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(commandFor(home, project)).parse(listOf("setup", "--scope", "project"))
        }

        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--scope", "project", "--remove"))
        }

        output.trim() shouldContain "removed"
        SetupService.isInstalledAt(project.resolve("opencode.json")) shouldBe false
    }

    @Test
    fun `an unknown agent exits 3 naming the supported ones`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        var code = -1
        val output = captureStdout {
            try {
                JdxCli().subcommands(commandFor(home, project))
                    .parse(listOf("setup", "--agent", "clippy"))
            } catch (e: SetupExit) {
                code = e.code
            }
        }

        code shouldBe 3
        output.trim() shouldContain "opencode|kilo"
    }

    @Test
    fun `a bad scope exits 3`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)

        val exit = shouldThrow<SetupExit> {
            captureStdout {
                JdxCli().subcommands(commandFor(home, project))
                    .parse(listOf("setup", "--scope", "user"))
            }
        }

        exit.code shouldBe 3
    }

    @Test
    fun `check and remove together exit 3`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)

        val exit = shouldThrow<SetupExit> {
            captureStdout {
                JdxCli().subcommands(commandFor(home, project))
                    .parse(listOf("setup", "--check", "--remove"))
            }
        }

        exit.code shouldBe 3
    }

    @Test
    fun `json carries the setup payload in the envelope`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val v1 = SetupService.OpencodeVersionInfo(
            SetupService.OpencodeVersion.V1,
            "opencode 1.18.3",
            "opencode",
        )
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project, v1))
                .parse(listOf("setup", "--scope", "project", "--json"))
        }

        val parsed = Json.parseToJsonElement(output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "setup"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        val result = parsed["result"]!!.jsonObject
        result["agent"]?.jsonPrimitive?.content shouldBe "opencode"
        result["scope"]?.jsonPrimitive?.content shouldBe "project"
        result["installed"]?.jsonPrimitive?.content shouldBe "true"
        result["opencodeVersion"]?.jsonPrimitive?.content shouldBe "v1"
        result["opencodeRaw"]?.jsonPrimitive?.content shouldBe "opencode 1.18.3"
    }

    @Test
    fun `exit codes follow the public contract over generated scopes`() {
        runBlocking {
            checkAll(Arb.of(SetupService.Scope.entries)) { scope ->
                setupExitCode(
                    SetupService.SetupOutcome.Installed(java.nio.file.Paths.get("p"), changed = true),
                ) shouldBe 0
                setupExitCode(
                    SetupService.SetupOutcome.Checked(java.nio.file.Paths.get("p"), installed = scope == SetupService.Scope.PROJECT),
                ) shouldBe if (scope == SetupService.Scope.PROJECT) 0 else 1
            }
        }
    }

    // -- Kilo Code wiring (`--agent kilo`) --

    @Test
    fun `kilo install writes the single entry and names the restart`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val output = captureStdout {
            JdxCli().subcommands(kiloCommandFor(home, project))
                .parse(listOf("setup", "--agent", "kilo", "--scope", "project"))
        }

        output.trim() shouldContain "kilo project setup installed"
        // Separator-aware: the rendered path uses `\` on Windows (#52).
        output.trim() shouldContain project.resolve(".kilo").resolve("kilo.json").toString()
        output.trim() shouldContain "restart Kilo Code"
        SetupService.isInstalledAt(
            project.resolve(".kilo/kilo.json"),
            SetupService.Agent.KILO,
        ) shouldBe true
    }

    @Test
    fun `kilo accepts every documented spelling`(@TempDir root: Path) {
        listOf("kilo", "Kilo", "kilo-code", "kilocode").forEachIndexed { index, spelling ->
            val case = root.resolve("case-$index").also { Files.createDirectories(it) }
            val (home, project) = fakeDirs(case)
            captureStdout {
                JdxCli().subcommands(kiloCommandFor(home, project))
                    .parse(listOf("setup", "--agent", spelling, "--scope", "project"))
            }

            SetupService.isInstalledAt(
                project.resolve(".kilo/kilo.json"),
                SetupService.Agent.KILO,
            ) shouldBe true
        }
    }

    @Test
    fun `a second kilo install is a no-op reporting already installed`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val args = listOf("setup", "--agent", "kilo", "--scope", "project")
        captureStdout { JdxCli().subcommands(kiloCommandFor(home, project)).parse(args) }

        val output = captureStdout {
            JdxCli().subcommands(kiloCommandFor(home, project)).parse(args)
        }

        output.trim() shouldContain "already installed"
    }

    @Test
    fun `kilo system scope writes under the fake home`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(kiloCommandFor(home, project))
                .parse(listOf("setup", "--agent", "kilo", "--scope", "system"))
        }

        SetupService.isInstalledAt(
            home.resolve(".config/kilo/kilo.json"),
            SetupService.Agent.KILO,
        ) shouldBe true
    }

    @Test
    fun `kilo check exits 1 when absent and 0 once installed without writing`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val check = listOf("setup", "--agent", "kilo", "--scope", "project", "--check")

        val absent = shouldThrow<SetupExit> {
            captureStdout { JdxCli().subcommands(kiloCommandFor(home, project)).parse(check) }
        }
        absent.code shouldBe 1
        Files.exists(project.resolve(".kilo/kilo.json")) shouldBe false

        captureStdout {
            JdxCli().subcommands(kiloCommandFor(home, project))
                .parse(listOf("setup", "--agent", "kilo", "--scope", "project"))
        }
        val installed = captureStdout {
            JdxCli().subcommands(kiloCommandFor(home, project)).parse(check)
        }
        installed.trim() shouldContain "kilo project setup installed"
    }

    @Test
    fun `kilo check hint names the kilo install command`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)

        var code = -1
        val output = captureStdout {
            try {
                JdxCli().subcommands(kiloCommandFor(home, project))
                    .parse(listOf("setup", "--agent", "kilo", "--scope", "system", "--check"))
            } catch (e: SetupExit) {
                code = e.code
            }
        }

        code shouldBe 1
        output.trim() shouldContain "jdx setup --agent kilo --scope system"
    }

    @Test
    fun `kilo remove uninstalls cleanly`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(kiloCommandFor(home, project))
                .parse(listOf("setup", "--agent", "kilo", "--scope", "project"))
        }

        val output = captureStdout {
            JdxCli().subcommands(kiloCommandFor(home, project))
                .parse(listOf("setup", "--agent", "kilo", "--scope", "project", "--remove"))
        }

        output.trim() shouldContain "kilo project setup removed"
        SetupService.isInstalledAt(
            project.resolve(".kilo/kilo.json"),
            SetupService.Agent.KILO,
        ) shouldBe false
    }

    @Test
    fun `kilo json carries the setup payload without an opencode line`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val output = captureStdout {
            JdxCli().subcommands(kiloCommandFor(home, project))
                .parse(listOf("setup", "--agent", "kilo", "--scope", "project", "--json"))
        }

        val parsed = Json.parseToJsonElement(output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "setup"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        val result = parsed["result"]!!.jsonObject
        result["agent"]?.jsonPrimitive?.content shouldBe "kilo"
        result["scope"]?.jsonPrimitive?.content shouldBe "project"
        result["installed"]?.jsonPrimitive?.content shouldBe "true"
        // No OpenCode line probe for Kilo: the nullable fields are absent
        // (`explicitNulls = false`), never null-marked.
        result.containsKey("opencodeVersion") shouldBe false
        result.containsKey("opencodeRaw") shouldBe false
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
