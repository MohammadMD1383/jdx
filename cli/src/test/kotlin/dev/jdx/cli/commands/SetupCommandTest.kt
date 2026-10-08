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
        claudeVersion: SetupService.ClaudeVersionInfo = SetupService.ClaudeVersionInfo(
            SetupService.ClaudeVersion.ABSENT,
        ),
        codexVersion: SetupService.CodexVersionInfo = SetupService.CodexVersionInfo(
            SetupService.CodexVersion.ABSENT,
        ),
        cursorVersion: SetupService.CursorVersionInfo = SetupService.CursorVersionInfo(
            SetupService.CursorVersion.ABSENT,
        ),
        copilotVersion: SetupService.CopilotVersionInfo = SetupService.CopilotVersionInfo(
            SetupService.CopilotVersion.ABSENT,
        ),
        antigravityVersion: SetupService.AntigravityVersionInfo = SetupService.AntigravityVersionInfo(
            SetupService.AntigravityVersion.ABSENT,
        ),
    ): SetupCommand = SetupCommand(
        // The Codex and Copilot system dirs are pinned under the fake home
        // so no test can leak into the real `~/.codex` / `~/.copilot` via
        // the ambient `$CODEX_HOME` / `$COPILOT_HOME`.
        serviceFactory = { _, _ ->
            SetupService(home, project, home.resolve(".codex"), home.resolve(".copilot"))
        },
        terminate = { throw SetupExit(it) },
        versionProbe = { version },
        claudeVersionProbe = { claudeVersion },
        cursorVersionProbe = { cursorVersion },
        codexVersionProbe = { codexVersion },
        copilotVersionProbe = { copilotVersion },
        antigravityVersionProbe = { antigravityVersion },
    )

    private fun kiloCommandFor(home: Path, project: Path): SetupCommand = SetupCommand(
        serviceFactory = { _, _ -> SetupService(home, project) },
        terminate = { throw SetupExit(it) },
        // Kilo wiring must never consult `opencode --version`: a throwing probe pins that.
        versionProbe = { throw AssertionError("kilo setup must not probe the opencode binary") },
    )

    private fun clineCommandFor(home: Path, project: Path): SetupCommand = SetupCommand(
        serviceFactory = { _, _ -> SetupService(home, project) },
        terminate = { throw SetupExit(it) },
        // Cline wiring must never consult either version probe: throwing probes pin that.
        versionProbe = { throw AssertionError("cline setup must not probe the opencode binary") },
        claudeVersionProbe = { throw AssertionError("cline setup must not probe the claude binary") },
    )

    private fun clinePath(home: Path): Path = home.resolve(".cline/data/settings/cline_mcp_settings.json")

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
        output.trim() shouldContain "opencode|claude-code|cursor|kilo|cline|codex|copilot|antigravity"
    }

    @Test
    fun `claude install writes dot mcp json and says what to do next`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "claude-code", "--scope", "project"))
        }

        output.trim() shouldContain "claude-code project setup installed"
        output.trim() shouldContain "mcpServers entry"
        output.trim() shouldContain "restart claude-code"
        output.trim() shouldContain "claude-code not found on PATH"
        SetupService.isInstalledAt(
            project.resolve(".mcp.json"),
            SetupService.Agent.CLAUDE_CODE,
        ) shouldBe true
    }

    @Test
    fun `claude accepts the bare claude alias`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "claude", "--scope", "project"))
        }

        SetupService.isInstalledAt(
            project.resolve(".mcp.json"),
            SetupService.Agent.CLAUDE_CODE,
        ) shouldBe true
    }

    @Test
    fun `claude install names the detected binary`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val present = SetupService.ClaudeVersionInfo(
            SetupService.ClaudeVersion.PRESENT,
            "1.0.33 (Claude Code)",
            "claude",
        )
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project, claudeVersion = present))
                .parse(listOf("setup", "--agent", "claude-code", "--scope", "project"))
        }

        output.trim() shouldContain "detected: claude-code (1.0.33 (Claude Code))"
    }

    @Test
    fun `a second claude install is a no-op reporting already installed`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val args = listOf("setup", "--agent", "claude-code", "--scope", "project")
        captureStdout { JdxCli().subcommands(commandFor(home, project)).parse(args) }

        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project)).parse(args)
        }

        output.trim() shouldContain "already installed"
    }

    @Test
    fun `claude system scope writes dot claude json under the fake home`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "claude-code", "--scope", "system"))
        }

        SetupService.isInstalledAt(
            home.resolve(".claude.json"),
            SetupService.Agent.CLAUDE_CODE,
        ) shouldBe true
    }

    @Test
    fun `claude check exits 1 when absent and 0 once installed`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val check = listOf("setup", "--agent", "claude-code", "--scope", "project", "--check")

        val absent = shouldThrow<SetupExit> {
            captureStdout { JdxCli().subcommands(commandFor(home, project)).parse(check) }
        }
        absent.code shouldBe 1

        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "claude-code", "--scope", "project"))
        }
        captureStdout { JdxCli().subcommands(commandFor(home, project)).parse(check) }
    }

    @Test
    fun `claude remove uninstalls cleanly`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "claude-code", "--scope", "project"))
        }

        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "claude-code", "--scope", "project", "--remove"))
        }

        output.trim() shouldContain "removed"
        SetupService.isInstalledAt(
            project.resolve(".mcp.json"),
            SetupService.Agent.CLAUDE_CODE,
        ) shouldBe false
    }

    @Test
    fun `claude json carries the claude payload in the envelope`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val present = SetupService.ClaudeVersionInfo(
            SetupService.ClaudeVersion.PRESENT,
            "1.0.33 (Claude Code)",
            "claude",
        )
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project, claudeVersion = present))
                .parse(listOf("setup", "--agent", "claude-code", "--scope", "project", "--json"))
        }

        val parsed = Json.parseToJsonElement(output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "setup"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        val result = parsed["result"]!!.jsonObject
        result["agent"]?.jsonPrimitive?.content shouldBe "claude-code"
        result["scope"]?.jsonPrimitive?.content shouldBe "project"
        result["installed"]?.jsonPrimitive?.content shouldBe "true"
        result["claudeVersion"]?.jsonPrimitive?.content shouldBe "present"
        result["claudeRaw"]?.jsonPrimitive?.content shouldBe "1.0.33 (Claude Code)"
    }

    // -- Cursor wiring (`--agent cursor`) --
    // Verified against a real install: `cursor-agent 2026.09.26-dd393fe`
    // (`agent mcp list` reads `.cursor/mcp.json` / `~/.cursor/mcp.json`).

    @Test
    fun `cursor install writes dot-cursor mcp json and says what to do next`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "cursor", "--scope", "project"))
        }

        output.trim() shouldContain "cursor project setup installed"
        output.trim() shouldContain "mcpServers entry"
        output.trim() shouldContain "restart cursor"
        output.trim() shouldContain "cursor not found on PATH"
        SetupService.isInstalledAt(
            project.resolve(".cursor/mcp.json"),
            SetupService.Agent.CURSOR,
        ) shouldBe true
    }

    @Test
    fun `cursor install names the detected binary`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val present = SetupService.CursorVersionInfo(
            SetupService.CursorVersion.PRESENT,
            "2026.09.26-dd393fe",
            "cursor-agent",
        )
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project, cursorVersion = present))
                .parse(listOf("setup", "--agent", "cursor", "--scope", "project"))
        }

        output.trim() shouldContain "detected: cursor (2026.09.26-dd393fe)"
    }

    @Test
    fun `a second cursor install is a no-op reporting already installed`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val args = listOf("setup", "--agent", "cursor", "--scope", "project")
        captureStdout { JdxCli().subcommands(commandFor(home, project)).parse(args) }

        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project)).parse(args)
        }

        output.trim() shouldContain "already installed"
    }

    @Test
    fun `cursor system scope writes under the fake home`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "cursor", "--scope", "system"))
        }

        SetupService.isInstalledAt(
            home.resolve(".cursor/mcp.json"),
            SetupService.Agent.CURSOR,
        ) shouldBe true
    }

    @Test
    fun `cursor check exits 1 when absent and 0 once installed`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val check = listOf("setup", "--agent", "cursor", "--scope", "project", "--check")

        val absent = shouldThrow<SetupExit> {
            captureStdout { JdxCli().subcommands(commandFor(home, project)).parse(check) }
        }
        absent.code shouldBe 1

        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "cursor", "--scope", "project"))
        }
        captureStdout { JdxCli().subcommands(commandFor(home, project)).parse(check) }
    }

    @Test
    fun `cursor remove uninstalls cleanly`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "cursor", "--scope", "project"))
        }

        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "cursor", "--scope", "project", "--remove"))
        }

        output.trim() shouldContain "removed"
        SetupService.isInstalledAt(
            project.resolve(".cursor/mcp.json"),
            SetupService.Agent.CURSOR,
        ) shouldBe false
    }

    @Test
    fun `cursor json carries the cursor payload in the envelope`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val present = SetupService.CursorVersionInfo(
            SetupService.CursorVersion.PRESENT,
            "2026.09.26-dd393fe",
            "cursor-agent",
        )
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project, cursorVersion = present))
                .parse(listOf("setup", "--agent", "cursor", "--scope", "project", "--json"))
        }

        val parsed = Json.parseToJsonElement(output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "setup"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        val result = parsed["result"]!!.jsonObject
        result["agent"]?.jsonPrimitive?.content shouldBe "cursor"
        result["scope"]?.jsonPrimitive?.content shouldBe "project"
        result["installed"]?.jsonPrimitive?.content shouldBe "true"
        result["cursorVersion"]?.jsonPrimitive?.content shouldBe "present"
        result["cursorRaw"]?.jsonPrimitive?.content shouldBe "2026.09.26-dd393fe"
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

    // -- Cline wiring (`--agent cline`, single global file) --

    @Test
    fun `cline install writes the global entry and names the restart`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val output = captureStdout {
            JdxCli().subcommands(clineCommandFor(home, project))
                .parse(listOf("setup", "--agent", "cline", "--scope", "system"))
        }

        output.trim() shouldContain "cline system setup installed"
        output.trim() shouldContain "cline_mcp_settings.json"
        output.trim() shouldContain "restart Cline"
        SetupService.isInstalledAt(
            clinePath(home),
            SetupService.Agent.CLINE,
        ) shouldBe true
    }

    // -- Codex CLI wiring (`--agent codex`) --

    @Test
    fun `codex install writes the toml table and says what to do next`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "codex", "--scope", "project"))
        }

        output.trim() shouldContain "codex project setup installed"
        output.trim() shouldContain "mcp_servers entry"
        output.trim() shouldContain "restart codex"
        output.trim() shouldContain "codex not found on PATH"
        SetupService.isInstalledAt(
            project.resolve(".codex/config.toml"),
            SetupService.Agent.CODEX,
        ) shouldBe true
    }

    @Test
    fun `cline project scope writes the same global file and says so`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val output = captureStdout {
            JdxCli().subcommands(clineCommandFor(home, project))
                .parse(listOf("setup", "--agent", "cline", "--scope", "project"))
        }

        // Cline keeps no project-level MCP file: the run wires the global
        // file and the output names it instead of a checkout-local path.
        output.trim() shouldContain "cline project setup installed"
        output.trim() shouldContain "global file"
        output.trim() shouldContain clinePath(home).toString()
        Files.exists(project.resolve(".cline/mcp.json")) shouldBe false
        SetupService.isInstalledAt(
            clinePath(home),
            SetupService.Agent.CLINE,
        ) shouldBe true
    }

    @Test
    fun `cline accepts every documented spelling`(@TempDir root: Path) {
        listOf("cline", "Cline", "cline-code", "clinecode").forEachIndexed { index, spelling ->
            val case = root.resolve("case-$index").also { Files.createDirectories(it) }
            val (home, project) = fakeDirs(case)
            captureStdout {
                JdxCli().subcommands(clineCommandFor(home, project))
                    .parse(listOf("setup", "--agent", spelling, "--scope", "system"))
            }

            SetupService.isInstalledAt(
                clinePath(home),
                SetupService.Agent.CLINE,
            ) shouldBe true
        }
    }

    @Test
    fun `codex accepts every documented spelling`(@TempDir root: Path) {
        listOf("codex", "Codex", "codex-cli", "codexcli").forEachIndexed { index, spelling ->
            val case = root.resolve("case-$index").also { Files.createDirectories(it) }
            val (home, project) = fakeDirs(case)
            captureStdout {
                JdxCli().subcommands(commandFor(home, project))
                    .parse(listOf("setup", "--agent", spelling, "--scope", "project"))
            }

            SetupService.isInstalledAt(
                project.resolve(".codex/config.toml"),
                SetupService.Agent.CODEX,
            ) shouldBe true
        }
    }

    @Test
    fun `a second cline install is a no-op reporting already installed`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val args = listOf("setup", "--agent", "cline", "--scope", "system")
        captureStdout { JdxCli().subcommands(clineCommandFor(home, project)).parse(args) }

        val output = captureStdout {
            JdxCli().subcommands(clineCommandFor(home, project)).parse(args)
        }

        output.trim() shouldContain "already installed"
    }

    @Test
    fun `codex install names the detected binary`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val present = SetupService.CodexVersionInfo(
            SetupService.CodexVersion.PRESENT,
            "codex-cli 0.157.1",
            "codex",
        )
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project, codexVersion = present))
                .parse(listOf("setup", "--agent", "codex", "--scope", "project"))
        }

        output.trim() shouldContain "detected: codex (codex-cli 0.157.1)"
    }

    @Test
    fun `a second codex install is a no-op reporting already installed`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val args = listOf("setup", "--agent", "codex", "--scope", "project")
        captureStdout { JdxCli().subcommands(commandFor(home, project)).parse(args) }

        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project)).parse(args)
        }

        output.trim() shouldContain "already installed"
    }

    @Test
    fun `cline check exits 1 when absent and 0 once installed without writing`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val check = listOf("setup", "--agent", "cline", "--scope", "system", "--check")

        val absent = shouldThrow<SetupExit> {
            captureStdout { JdxCli().subcommands(clineCommandFor(home, project)).parse(check) }
        }
        absent.code shouldBe 1
        Files.exists(clinePath(home)) shouldBe false

        captureStdout {
            JdxCli().subcommands(clineCommandFor(home, project))
                .parse(listOf("setup", "--agent", "cline", "--scope", "system"))
        }
        val installed = captureStdout {
            JdxCli().subcommands(clineCommandFor(home, project)).parse(check)
        }
        installed.trim() shouldContain "cline system setup installed"
    }

    @Test
    fun `cline check hint names the cline install command`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)

        var code = -1
        val output = captureStdout {
            try {
                JdxCli().subcommands(clineCommandFor(home, project))
                    .parse(listOf("setup", "--agent", "cline", "--scope", "system", "--check"))
            } catch (e: SetupExit) {
                code = e.code
            }
        }

        code shouldBe 1
        output.trim() shouldContain "jdx setup --agent cline --scope system"
    }

    @Test
    fun `codex system scope writes under the fake home`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "codex", "--scope", "system"))
        }

        SetupService.isInstalledAt(
            home.resolve(".codex/config.toml"),
            SetupService.Agent.CODEX,
        ) shouldBe true
    }

    @Test
    fun `codex check exits 1 when absent and 0 once installed without writing`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val check = listOf("setup", "--agent", "codex", "--scope", "project", "--check")

        val absent = shouldThrow<SetupExit> {
            captureStdout { JdxCli().subcommands(commandFor(home, project)).parse(check) }
        }
        absent.code shouldBe 1
        Files.exists(project.resolve(".codex/config.toml")) shouldBe false

        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "codex", "--scope", "project"))
        }
        val installed = captureStdout {
            JdxCli().subcommands(commandFor(home, project)).parse(check)
        }
        installed.trim() shouldContain "codex project setup installed"
    }

    @Test
    fun `codex check hint names the codex install command`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)

        var code = -1
        val output = captureStdout {
            try {
                JdxCli().subcommands(commandFor(home, project))
                    .parse(listOf("setup", "--agent", "codex", "--scope", "system", "--check"))
            } catch (e: SetupExit) {
                code = e.code
            }
        }

        code shouldBe 1
        output.trim() shouldContain "jdx setup --agent codex --scope system"
    }

    @Test
    fun `cline remove uninstalls cleanly`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(clineCommandFor(home, project))
                .parse(listOf("setup", "--agent", "cline", "--scope", "system"))
        }

        val output = captureStdout {
            JdxCli().subcommands(clineCommandFor(home, project))
                .parse(listOf("setup", "--agent", "cline", "--scope", "system", "--remove"))
        }

        output.trim() shouldContain "cline system setup removed"
        SetupService.isInstalledAt(
            clinePath(home),
            SetupService.Agent.CLINE,
        ) shouldBe false
    }

    @Test
    fun `codex remove uninstalls cleanly`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "codex", "--scope", "project"))
        }

        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "codex", "--scope", "project", "--remove"))
        }

        output.trim() shouldContain "codex project setup removed"
        SetupService.isInstalledAt(
            project.resolve(".codex/config.toml"),
            SetupService.Agent.CODEX,
        ) shouldBe false
    }

    @Test
    fun `cline json carries the setup payload without version probes`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val output = captureStdout {
            JdxCli().subcommands(clineCommandFor(home, project))
                .parse(listOf("setup", "--agent", "cline", "--scope", "system", "--json"))
        }

        val parsed = Json.parseToJsonElement(output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "setup"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        val result = parsed["result"]!!.jsonObject
        result["agent"]?.jsonPrimitive?.content shouldBe "cline"
        result["scope"]?.jsonPrimitive?.content shouldBe "system"
        result["installed"]?.jsonPrimitive?.content shouldBe "true"
        // No version probes for Cline: the nullable fields are absent
        // (`explicitNulls = false`), never null-marked.
        result.containsKey("opencodeVersion") shouldBe false
        result.containsKey("opencodeRaw") shouldBe false
        result.containsKey("claudeVersion") shouldBe false
        result.containsKey("claudeRaw") shouldBe false
        result.containsKey("codexVersion") shouldBe false
        result.containsKey("codexRaw") shouldBe false
    }

    @Test
    fun `codex json carries the setup payload without other agent lines`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val present = SetupService.CodexVersionInfo(
            SetupService.CodexVersion.PRESENT,
            "codex-cli 0.157.1",
            "codex",
        )
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project, codexVersion = present))
                .parse(listOf("setup", "--agent", "codex", "--scope", "project", "--json"))
        }

        val parsed = Json.parseToJsonElement(output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "setup"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        val result = parsed["result"]!!.jsonObject
        result["agent"]?.jsonPrimitive?.content shouldBe "codex"
        result["scope"]?.jsonPrimitive?.content shouldBe "project"
        result["installed"]?.jsonPrimitive?.content shouldBe "true"
        result["codexVersion"]?.jsonPrimitive?.content shouldBe "present"
        result["codexRaw"]?.jsonPrimitive?.content shouldBe "codex-cli 0.157.1"
        // No other agent's line probe: the nullable fields are absent
        // (`explicitNulls = false`), never null-marked.
        result.containsKey("opencodeVersion") shouldBe false
        result.containsKey("opencodeRaw") shouldBe false
        result.containsKey("claudeVersion") shouldBe false
        result.containsKey("claudeRaw") shouldBe false
        result.containsKey("copilotVersion") shouldBe false
        result.containsKey("copilotRaw") shouldBe false
    }

    // -- GitHub Copilot CLI wiring (`--agent copilot`) --

    @Test
    fun `copilot install writes the mcpServers entry and says what to do next`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "copilot", "--scope", "project"))
        }

        output.trim() shouldContain "copilot project setup installed"
        output.trim() shouldContain "mcpServers entry"
        output.trim() shouldContain "restart copilot"
        output.trim() shouldContain "copilot not found on PATH"
        SetupService.isInstalledAt(
            project.resolve(".mcp.json"),
            SetupService.Agent.COPILOT,
        ) shouldBe true
    }

    @Test
    fun `copilot accepts every documented spelling`(@TempDir root: Path) {
        listOf("copilot", "Copilot", "github-copilot", "copilot-cli").forEachIndexed { index, spelling ->
            val case = root.resolve("case-$index").also { Files.createDirectories(it) }
            val (home, project) = fakeDirs(case)
            captureStdout {
                JdxCli().subcommands(commandFor(home, project))
                    .parse(listOf("setup", "--agent", spelling, "--scope", "project"))
            }

            SetupService.isInstalledAt(
                project.resolve(".mcp.json"),
                SetupService.Agent.COPILOT,
            ) shouldBe true
        }
    }

    @Test
    fun `copilot install names the detected binary`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val present = SetupService.CopilotVersionInfo(
            SetupService.CopilotVersion.PRESENT,
            "GitHub Copilot CLI 1.0.88.",
            "copilot",
        )
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project, copilotVersion = present))
                .parse(listOf("setup", "--agent", "copilot", "--scope", "project"))
        }

        output.trim() shouldContain "detected: copilot (GitHub Copilot CLI 1.0.88.)"
    }

    @Test
    fun `a second copilot install is a no-op reporting already installed`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val args = listOf("setup", "--agent", "copilot", "--scope", "project")
        captureStdout { JdxCli().subcommands(commandFor(home, project)).parse(args) }

        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project)).parse(args)
        }

        output.trim() shouldContain "already installed"
    }

    @Test
    fun `copilot system scope writes under the fake home`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "copilot", "--scope", "system"))
        }

        SetupService.isInstalledAt(
            home.resolve(".copilot/mcp-config.json"),
            SetupService.Agent.COPILOT,
        ) shouldBe true
    }

    @Test
    fun `copilot check exits 1 when absent and 0 once installed without writing`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val check = listOf("setup", "--agent", "copilot", "--scope", "project", "--check")

        val absent = shouldThrow<SetupExit> {
            captureStdout { JdxCli().subcommands(commandFor(home, project)).parse(check) }
        }
        absent.code shouldBe 1
        Files.exists(project.resolve(".mcp.json")) shouldBe false

        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "copilot", "--scope", "project"))
        }
        val installed = captureStdout {
            JdxCli().subcommands(commandFor(home, project)).parse(check)
        }
        installed.trim() shouldContain "copilot project setup installed"
    }

    @Test
    fun `copilot check hint names the copilot install command`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)

        var code = -1
        val output = captureStdout {
            try {
                JdxCli().subcommands(commandFor(home, project))
                    .parse(listOf("setup", "--agent", "copilot", "--scope", "system", "--check"))
            } catch (e: SetupExit) {
                code = e.code
            }
        }

        code shouldBe 1
        output.trim() shouldContain "jdx setup --agent copilot --scope system"
    }

    @Test
    fun `copilot remove uninstalls cleanly`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "copilot", "--scope", "project"))
        }

        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "copilot", "--scope", "project", "--remove"))
        }

        output.trim() shouldContain "copilot project setup removed"
        SetupService.isInstalledAt(
            project.resolve(".mcp.json"),
            SetupService.Agent.COPILOT,
        ) shouldBe false
    }

    @Test
    fun `copilot json carries the setup payload without other agent lines`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val present = SetupService.CopilotVersionInfo(
            SetupService.CopilotVersion.PRESENT,
            "GitHub Copilot CLI 1.0.88.",
            "copilot",
        )
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project, copilotVersion = present))
                .parse(listOf("setup", "--agent", "copilot", "--scope", "project", "--json"))
        }

        val parsed = Json.parseToJsonElement(output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "setup"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        val result = parsed["result"]!!.jsonObject
        result["agent"]?.jsonPrimitive?.content shouldBe "copilot"
        result["scope"]?.jsonPrimitive?.content shouldBe "project"
        result["installed"]?.jsonPrimitive?.content shouldBe "true"
        result["copilotVersion"]?.jsonPrimitive?.content shouldBe "present"
        result["copilotRaw"]?.jsonPrimitive?.content shouldBe "GitHub Copilot CLI 1.0.88."
        // No other agent's line probe: the nullable fields are absent
        // (`explicitNulls = false`), never null-marked.
        result.containsKey("opencodeVersion") shouldBe false
        result.containsKey("opencodeRaw") shouldBe false
        result.containsKey("claudeVersion") shouldBe false
        result.containsKey("claudeRaw") shouldBe false
        result.containsKey("codexVersion") shouldBe false
        result.containsKey("codexRaw") shouldBe false
    }

    // -- Antigravity wiring (`--agent antigravity`) --
    // Verified against a real install: `agy` 1.3.0 (`agy mcp add jdx --
    // jdx mcp` writes `~/.gemini/config/mcp_config.json`; `agy mcp list`
    // detects the entry; workspace overrides live in
    // `.agents/mcp_config.json`).

    @Test
    fun `antigravity install writes dot-agents mcp config and says what to do next`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "antigravity", "--scope", "project"))
        }

        output.trim() shouldContain "antigravity project setup installed"
        output.trim() shouldContain "mcpServers entry"
        output.trim() shouldContain "restart antigravity"
        output.trim() shouldContain "antigravity not found on PATH"
        SetupService.isInstalledAt(
            project.resolve(".agents/mcp_config.json"),
            SetupService.Agent.ANTIGRAVITY,
        ) shouldBe true
    }

    @Test
    fun `antigravity accepts every documented spelling`(@TempDir root: Path) {
        listOf("antigravity", "Antigravity", "agy", "AGY", "antigravity-cli").forEachIndexed { index, spelling ->
            val case = root.resolve("case-$index").also { Files.createDirectories(it) }
            val (home, project) = fakeDirs(case)
            captureStdout {
                JdxCli().subcommands(commandFor(home, project))
                    .parse(listOf("setup", "--agent", spelling, "--scope", "project"))
            }

            SetupService.isInstalledAt(
                project.resolve(".agents/mcp_config.json"),
                SetupService.Agent.ANTIGRAVITY,
            ) shouldBe true
        }
    }

    @Test
    fun `antigravity install names the detected binary`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val present = SetupService.AntigravityVersionInfo(
            SetupService.AntigravityVersion.PRESENT,
            "1.3.0",
            "agy",
        )
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project, antigravityVersion = present))
                .parse(listOf("setup", "--agent", "antigravity", "--scope", "project"))
        }

        output.trim() shouldContain "detected: antigravity (1.3.0)"
    }

    @Test
    fun `a second antigravity install is a no-op reporting already installed`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val args = listOf("setup", "--agent", "antigravity", "--scope", "project")
        captureStdout { JdxCli().subcommands(commandFor(home, project)).parse(args) }

        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project)).parse(args)
        }

        output.trim() shouldContain "already installed"
    }

    @Test
    fun `antigravity system scope writes under the fake home`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "antigravity", "--scope", "system"))
        }

        SetupService.isInstalledAt(
            home.resolve(".gemini/config/mcp_config.json"),
            SetupService.Agent.ANTIGRAVITY,
        ) shouldBe true
    }

    @Test
    fun `antigravity check exits 1 when absent and 0 once installed without writing`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val check = listOf("setup", "--agent", "antigravity", "--scope", "project", "--check")

        val absent = shouldThrow<SetupExit> {
            captureStdout { JdxCli().subcommands(commandFor(home, project)).parse(check) }
        }
        absent.code shouldBe 1
        Files.exists(project.resolve(".agents/mcp_config.json")) shouldBe false

        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "antigravity", "--scope", "project"))
        }
        val installed = captureStdout {
            JdxCli().subcommands(commandFor(home, project)).parse(check)
        }
        installed.trim() shouldContain "antigravity project setup installed"
    }

    @Test
    fun `antigravity check hint names the antigravity install command`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)

        var code = -1
        val output = captureStdout {
            try {
                JdxCli().subcommands(commandFor(home, project))
                    .parse(listOf("setup", "--agent", "antigravity", "--scope", "system", "--check"))
            } catch (e: SetupExit) {
                code = e.code
            }
        }

        code shouldBe 1
        output.trim() shouldContain "jdx setup --agent antigravity --scope system"
    }

    @Test
    fun `antigravity remove uninstalls cleanly`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "antigravity", "--scope", "project"))
        }

        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project))
                .parse(listOf("setup", "--agent", "antigravity", "--scope", "project", "--remove"))
        }

        output.trim() shouldContain "antigravity project setup removed"
        SetupService.isInstalledAt(
            project.resolve(".agents/mcp_config.json"),
            SetupService.Agent.ANTIGRAVITY,
        ) shouldBe false
    }

    @Test
    fun `antigravity json carries the setup payload without other agent lines`(@TempDir root: Path) {
        val (home, project) = fakeDirs(root)
        val present = SetupService.AntigravityVersionInfo(
            SetupService.AntigravityVersion.PRESENT,
            "1.3.0",
            "agy",
        )
        val output = captureStdout {
            JdxCli().subcommands(commandFor(home, project, antigravityVersion = present))
                .parse(listOf("setup", "--agent", "antigravity", "--scope", "project", "--json"))
        }

        val parsed = Json.parseToJsonElement(output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "setup"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        val result = parsed["result"]!!.jsonObject
        result["agent"]?.jsonPrimitive?.content shouldBe "antigravity"
        result["scope"]?.jsonPrimitive?.content shouldBe "project"
        result["installed"]?.jsonPrimitive?.content shouldBe "true"
        result["antigravityVersion"]?.jsonPrimitive?.content shouldBe "present"
        result["antigravityRaw"]?.jsonPrimitive?.content shouldBe "1.3.0"
        // No other agent's line probe: the nullable fields are absent
        // (`explicitNulls = false`), never null-marked.
        result.containsKey("opencodeVersion") shouldBe false
        result.containsKey("opencodeRaw") shouldBe false
        result.containsKey("claudeVersion") shouldBe false
        result.containsKey("claudeRaw") shouldBe false
        result.containsKey("codexVersion") shouldBe false
        result.containsKey("codexRaw") shouldBe false
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
