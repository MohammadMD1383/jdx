package dev.jdx.cli.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `SetupService` over fake homes (issue #33 family): install/check/remove for
 * the OpenCode, Claude Code, Cursor, Kilo Code, Cline, Codex CLI, GitHub
 * Copilot CLI, and Antigravity backends, merge discipline, and the JSONC
 * stripper. Every home and project dir is a fresh temp dir — no test touches
 * the real home.
 *
 * Merged file bytes are pinned separately in `SetupGoldenTest` (tier 2,
 * `src/test/resources/golden/setup/`).
 */
class SetupServiceTest {

    private data class FakeDirs(val home: Path, val project: Path)

    private fun fakeDirs(root: Path): FakeDirs {
        val home = root.resolve("home").also { Files.createDirectories(it) }
        val project = root.resolve("project").also { Files.createDirectories(it) }
        return FakeDirs(home, project)
    }

    private fun projectRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.OPENCODE,
        scope = SetupService.Scope.PROJECT,
        check = check,
        remove = remove,
    )

    private fun systemRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.OPENCODE,
        scope = SetupService.Scope.SYSTEM,
        check = check,
        remove = remove,
    )

    private fun kiloProjectRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.KILO,
        scope = SetupService.Scope.PROJECT,
        check = check,
        remove = remove,
    )

    private fun kiloSystemRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.KILO,
        scope = SetupService.Scope.SYSTEM,
        check = check,
        remove = remove,
    )

    @Test
    fun `a fresh project install creates opencode dot json with the jdx entry`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(projectRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.changed shouldBe true
        installed.path shouldBe dirs.project.resolve("opencode.json")
        setupExitCode(outcome) shouldBe 0
        val stored = Json.parseToJsonElement(Files.readString(installed.path)).jsonObject
        stored["\$schema"]?.jsonPrimitive?.content shouldBe "https://opencode.ai/config.json"
        SetupService.isInstalledRoot(stored) shouldBe true
    }

    @Test
    fun `a fresh install writes both the v1 and v2 entries`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(projectRequest()) as SetupService.SetupOutcome.Installed

        val stored = Json.parseToJsonElement(Files.readString(outcome.path)).jsonObject
        val v1 = stored["mcp"]?.jsonObject?.get("jdx")?.jsonObject
        v1?.get("type")?.jsonPrimitive?.content shouldBe "local"
        v1?.get("command").toString() shouldBe """["jdx","mcp"]"""
        v1?.get("enabled")?.jsonPrimitive?.content shouldBe "true"
        val v2 = stored["mcp"]?.jsonObject?.get("servers")?.jsonObject?.get("jdx")?.jsonObject
        v2?.get("type")?.jsonPrimitive?.content shouldBe "local"
        v2?.get("command").toString() shouldBe """["jdx","mcp"]"""
        v2?.get("disabled")?.jsonPrimitive?.content shouldBe "false"
        SetupService.isV1Installed(stored) shouldBe true
        SetupService.isV2Installed(stored) shouldBe true
    }

    @Test
    fun `a legacy v1-only install is completed with the v2 entry and then is a no-op`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve("opencode.json"),
            """{"mcp":{"jdx":{"type":"local","command":["jdx","mcp"],"enabled":true}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)

        // Lenient probe: a v1-only file still counts as wired (backwards compat).
        SetupService.isInstalledAt(dirs.project.resolve("opencode.json")) shouldBe true

        val completed = service.run(projectRequest()) as SetupService.SetupOutcome.Installed
        completed.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(completed.path)).jsonObject
        SetupService.isV1Installed(stored) shouldBe true
        SetupService.isV2Installed(stored) shouldBe true

        val again = service.run(projectRequest()) as SetupService.SetupOutcome.Installed
        again.changed shouldBe false
    }

    @Test
    fun `a v2-only install is completed with the v1 entry`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve("opencode.json"),
            """{"mcp":{"servers":{"jdx":{"type":"local","command":["jdx","mcp"],"disabled":false}}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)

        SetupService.isInstalledAt(dirs.project.resolve("opencode.json")) shouldBe true

        val completed = service.run(projectRequest()) as SetupService.SetupOutcome.Installed
        completed.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(completed.path)).jsonObject
        SetupService.isV1Installed(stored) shouldBe true
        SetupService.isV2Installed(stored) shouldBe true
    }

    @Test
    fun `install preserves other servers in both the v1 map and the v2 namespace`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve("opencode.json"),
            """{"mcp":{"other":{"type":"local","command":["other"]},"servers":{"v2other":{"type":"local","command":["v2other"]}}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(projectRequest()) as SetupService.SetupOutcome.Installed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(outcome.path)).jsonObject
        stored["mcp"]?.jsonObject?.get("other")?.jsonObject
            ?.get("command").toString() shouldBe """["other"]"""
        stored["mcp"]?.jsonObject?.get("servers")?.jsonObject?.get("v2other")?.jsonObject
            ?.get("command").toString() shouldBe """["v2other"]"""
        SetupService.isV1Installed(stored) shouldBe true
        SetupService.isV2Installed(stored) shouldBe true
    }

    @Test
    fun `install leaves a v1 server literally named servers untouched`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve("opencode.json"),
            """{"mcp":{"servers":{"type":"local","command":["mine"]}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(projectRequest()) as SetupService.SetupOutcome.Installed

        val stored = Json.parseToJsonElement(Files.readString(outcome.path)).jsonObject
        // The foreign server wins over the v2 namespace (never clobber); v1 is still wired.
        stored["mcp"]?.jsonObject?.get("servers")?.jsonObject
            ?.get("command").toString() shouldBe """["mine"]"""
        SetupService.isV1Installed(stored) shouldBe true
    }

    @Test
    fun `remove deletes both entries and drops the emptied servers namespace`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)
        service.run(projectRequest())

        val outcome = service.run(projectRequest(remove = true)) as SetupService.SetupOutcome.Removed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(dirs.project.resolve("opencode.json"))).jsonObject
        stored.containsKey("mcp") shouldBe false
        SetupService.isInstalledAt(dirs.project.resolve("opencode.json")) shouldBe false
    }

    @Test
    fun `remove keeps other servers in both maps`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve("opencode.json"),
            """{"mcp":{"other":{"type":"local","command":["other"]},"servers":{"v2other":{"type":"local","command":["v2other"]}}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)
        service.run(projectRequest())

        val outcome = service.run(projectRequest(remove = true)) as SetupService.SetupOutcome.Removed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(dirs.project.resolve("opencode.json"))).jsonObject
        stored["mcp"]?.jsonObject?.containsKey("jdx") shouldBe false
        stored["mcp"]?.jsonObject?.containsKey("other") shouldBe true
        val servers = stored["mcp"]?.jsonObject?.get("servers")?.jsonObject
        servers?.containsKey("jdx") shouldBe false
        servers?.containsKey("v2other") shouldBe true
        SetupService.isInstalledAt(dirs.project.resolve("opencode.json")) shouldBe false
    }

    @Test
    fun `a hostile command word never throws the installed probe`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve("opencode.json"),
            "{\"mcp\":{\"jdx\":{\"type\":\"local\",\"command\":[\"\u0000\",\"mcp\"]}}}",
        )

        SetupService.isInstalledAt(dirs.project.resolve("opencode.json")) shouldBe false
    }

    @Test
    fun `a second install is a byte-identical no-op`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val first = service.run(projectRequest()) as SetupService.SetupOutcome.Installed
        val before = Files.readAllBytes(first.path)
        val second = service.run(projectRequest())

        val installed = second as SetupService.SetupOutcome.Installed
        installed.changed shouldBe false
        Files.readAllBytes(first.path) shouldBe before
    }

    @Test
    fun `check reports without writing`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val absent = service.run(projectRequest(check = true))
        absent as SetupService.SetupOutcome.Checked
        absent.installed shouldBe false
        setupExitCode(absent) shouldBe 1
        Files.exists(dirs.project.resolve("opencode.json")) shouldBe false

        service.run(projectRequest())
        val present = service.run(projectRequest(check = true))
        present as SetupService.SetupOutcome.Checked
        present.installed shouldBe true
        setupExitCode(present) shouldBe 0
    }

    @Test
    fun `install merges and never clobbers unrelated entries`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve("opencode.json"),
            """{"${'$'}schema":"https://opencode.ai/config.json","model":"anthropic/claude-sonnet-4-6","mcp":{"playwright":{"type":"local","command":["npx","-y","@playwright/mcp"],"enabled":true}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(projectRequest())

        (outcome as SetupService.SetupOutcome.Installed).changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(outcome.path)).jsonObject
        stored["model"]?.jsonPrimitive?.content shouldBe "anthropic/claude-sonnet-4-6"
        stored["mcp"]?.jsonObject?.get("playwright")?.jsonObject
            ?.get("command")?.toString() shouldBe """["npx","-y","@playwright/mcp"]"""
        SetupService.isInstalledRoot(stored) shouldBe true
    }

    @Test
    fun `install reads jsonc with comments and trailing commas`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve("opencode.jsonc"),
            "{\n" +
                "  // project wiring\n" +
                "  \"model\": \"anthropic/claude-sonnet-4-6\",\n" +
                "  \"mcp\": {\n" +
                "    /* disabled legacy entry */\n" +
                "    \"old-server\": { \"enabled\": false },\n" +
                "  },\n" +
                "}\n",
        )
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(projectRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.path shouldBe dirs.project.resolve("opencode.jsonc")
        val stored = Json.parseToJsonElement(Files.readString(installed.path)).jsonObject
        stored["model"]?.jsonPrimitive?.content shouldBe "anthropic/claude-sonnet-4-6"
        stored["mcp"]?.jsonObject?.get("old-server")?.jsonObject
            ?.get("enabled").toString() shouldBe "false"
        SetupService.isInstalledRoot(stored) shouldBe true
    }

    @Test
    fun `remove deletes only the jdx entry`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)
        service.run(projectRequest())
        val path = dirs.project.resolve("opencode.json")
        val withOther = SetupService.mergeInstall(
            Json.parseToJsonElement(Files.readString(path)).jsonObject,
        ).let { root ->
            val mcp = root["mcp"]!!.jsonObject.toMutableMap()
            mcp["other"] = Json.parseToJsonElement("""{"type":"local","command":["other"],"enabled":true}""")
            JsonObject(root.toMutableMap().also { it["mcp"] = JsonObject(mcp) })
        }
        Files.writeString(path, withOther.toString())

        val outcome = service.run(projectRequest(remove = true))

        val removed = outcome as SetupService.SetupOutcome.Removed
        removed.changed shouldBe true
        setupExitCode(outcome) shouldBe 0
        val stored = Json.parseToJsonElement(Files.readString(path)).jsonObject
        stored["mcp"]?.jsonObject?.containsKey("jdx") shouldBe false
        stored["mcp"]?.jsonObject?.containsKey("other") shouldBe true
    }

    @Test
    fun `remove drops an emptied mcp object and is a no-op when absent`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)
        service.run(projectRequest())

        val outcome = service.run(projectRequest(remove = true))

        (outcome as SetupService.SetupOutcome.Removed).changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(dirs.project.resolve("opencode.json"))).jsonObject
        stored.containsKey("mcp") shouldBe false

        val again = service.run(projectRequest(remove = true))
        (again as SetupService.SetupOutcome.Removed).changed shouldBe false
        setupExitCode(again) shouldBe 0
    }

    @Test
    fun `a corrupt config exits 5 and names the file`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(dirs.project.resolve("opencode.json"), "{ not json,")
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(projectRequest())

        outcome as SetupService.SetupOutcome.Corrupt
        setupExitCode(outcome) shouldBe 5
    }

    @Test
    fun `system scope prefers the existing jsonc and creates json otherwise`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val created = service.run(systemRequest()) as SetupService.SetupOutcome.Installed
        created.path shouldBe dirs.home.resolve(".config/opencode/opencode.json")

        Files.writeString(
            dirs.home.resolve(".config/opencode/opencode.jsonc"),
            """{"${'$'}schema":"https://opencode.ai/config.json"}""",
        )
        val merged = service.run(systemRequest()) as SetupService.SetupOutcome.Installed
        merged.path shouldBe dirs.home.resolve(".config/opencode/opencode.jsonc")
        SetupService.isInstalledAt(merged.path) shouldBe true
    }

    @Test
    fun `project scope walks up to the nearest existing config`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(dirs.project.resolve("opencode.jsonc"), "{}")
        val nested = dirs.project.resolve("a/b").also { Files.createDirectories(it) }
        val service = SetupService(dirs.home, nested)

        val outcome = service.run(projectRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.path shouldBe dirs.project.resolve("opencode.jsonc")
        Files.exists(nested.resolve("opencode.json")) shouldBe false
    }

    @Test
    fun `agent and scope parsing accept documented spellings`() {
        SetupService.parseAgent(null) shouldBe SetupService.Agent.OPENCODE
        SetupService.parseAgent("OpenCode") shouldBe SetupService.Agent.OPENCODE
        SetupService.parseAgent("OPENCODE") shouldBe SetupService.Agent.OPENCODE
        SetupService.parseAgent("open-code") shouldBe SetupService.Agent.OPENCODE
        SetupService.parseAgent("claude-code") shouldBe SetupService.Agent.CLAUDE_CODE
        SetupService.parseAgent("Claude Code") shouldBe SetupService.Agent.CLAUDE_CODE
        SetupService.parseAgent("claudecode") shouldBe SetupService.Agent.CLAUDE_CODE
        SetupService.parseAgent("claude") shouldBe SetupService.Agent.CLAUDE_CODE
        SetupService.parseAgent("CLAUDE_CODE") shouldBe SetupService.Agent.CLAUDE_CODE
        SetupService.parseAgent("kilo") shouldBe SetupService.Agent.KILO
        SetupService.parseAgent("Kilo") shouldBe SetupService.Agent.KILO
        SetupService.parseAgent("kilo-code") shouldBe SetupService.Agent.KILO
        SetupService.parseAgent("kilocode") shouldBe SetupService.Agent.KILO
        SetupService.parseAgent("KILO_CODE") shouldBe SetupService.Agent.KILO
        SetupService.parseAgent("clippy") shouldBe null
        SetupService.parseScope(null) shouldBe SetupService.Scope.PROJECT
        SetupService.parseScope("system") shouldBe SetupService.Scope.SYSTEM
        SetupService.parseScope("PROJECT") shouldBe SetupService.Scope.PROJECT
        SetupService.parseScope("user") shouldBe null
    }

    @Test
    fun `doctor-style probes see project and system installs`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val projectPath = SetupService.projectConfigPath(dirs.project)
        val systemPath = SetupService.systemConfigPath(dirs.home)
        SetupService.isInstalledAt(projectPath) shouldBe false
        SetupService.isInstalledAt(systemPath) shouldBe false

        SetupService(dirs.home, dirs.project).run(projectRequest())
        SetupService.isInstalledAt(projectPath) shouldBe true
        SetupService.isInstalledAt(systemPath) shouldBe false

        SetupService(dirs.home, dirs.project).run(systemRequest())
        SetupService.isInstalledAt(systemPath) shouldBe true
    }

    // -- Kilo Code backend: single `mcp.jdx` entry in `kilo.json[c]` --

    @Test
    fun `a fresh kilo project install creates dot-kilo kilo dot json with only the single entry`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(kiloProjectRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.changed shouldBe true
        installed.path shouldBe dirs.project.resolve(".kilo/kilo.json")
        setupExitCode(outcome) shouldBe 0
        val stored = Json.parseToJsonElement(Files.readString(installed.path)).jsonObject
        // No OpenCode `$schema` on a fresh Kilo file, and no v2 namespace.
        stored.containsKey("\$schema") shouldBe false
        stored["mcp"]?.jsonObject?.containsKey("servers") shouldBe false
        SetupService.isInstalledRootFor(SetupService.Agent.KILO, stored) shouldBe true
        SetupService.isV1Installed(stored) shouldBe true
        val entry = stored["mcp"]?.jsonObject?.get("jdx")?.jsonObject
        entry?.get("type")?.jsonPrimitive?.content shouldBe "local"
        entry?.get("command").toString() shouldBe """["jdx","mcp"]"""
        entry?.get("enabled")?.jsonPrimitive?.content shouldBe "true"
    }

    @Test
    fun `kilo install merges and never writes the v2 namespace`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve("kilo.json"),
            """{"model":"kilo","mcp":{"other":{"type":"local","command":["other"],"enabled":true},"servers":{"type":"local","command":["mine"]}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(kiloProjectRequest())

        (outcome as SetupService.SetupOutcome.Installed).changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(outcome.path)).jsonObject
        stored["model"]?.jsonPrimitive?.content shouldBe "kilo"
        stored["mcp"]?.jsonObject?.get("other")?.jsonObject
            ?.get("command")?.toString() shouldBe """["other"]"""
        // A foreign `servers` key has no OpenCode v2 meaning in a Kilo config: untouched.
        stored["mcp"]?.jsonObject?.get("servers")?.jsonObject
            ?.get("command")?.toString() shouldBe """["mine"]"""
        SetupService.isInstalledRootFor(SetupService.Agent.KILO, stored) shouldBe true
    }

    @Test
    fun `kilo install over a root-level file targets that file`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(dirs.project.resolve("kilo.jsonc"), "{}")
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(kiloProjectRequest())

        (outcome as SetupService.SetupOutcome.Installed).path shouldBe dirs.project.resolve("kilo.jsonc")
        Files.exists(dirs.project.resolve(".kilo/kilo.json")) shouldBe false
    }

    @Test
    fun `kilo project scope prefers the dot-kilo variant and walks up`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.createDirectories(dirs.project.resolve(".kilo"))
        Files.writeString(dirs.project.resolve(".kilo/kilo.json"), "{}")
        Files.writeString(dirs.project.resolve("kilo.json"), "{}")
        val nested = dirs.project.resolve("a/b").also { Files.createDirectories(it) }
        val service = SetupService(dirs.home, nested)

        val outcome = service.run(kiloProjectRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.path shouldBe dirs.project.resolve(".kilo/kilo.json")
        Files.exists(nested.resolve(".kilo/kilo.json")) shouldBe false
        SetupService.isInstalledAt(installed.path, SetupService.Agent.KILO) shouldBe true
    }

    @Test
    fun `kilo system scope prefers the existing jsonc and creates json otherwise`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val created = service.run(kiloSystemRequest()) as SetupService.SetupOutcome.Installed
        created.path shouldBe dirs.home.resolve(".config/kilo/kilo.json")

        Files.writeString(
            dirs.home.resolve(".config/kilo/kilo.jsonc"),
            """{}""",
        )
        val merged = service.run(kiloSystemRequest()) as SetupService.SetupOutcome.Installed
        merged.path shouldBe dirs.home.resolve(".config/kilo/kilo.jsonc")
        SetupService.isInstalledAt(merged.path, SetupService.Agent.KILO) shouldBe true
    }

    @Test
    fun `kilo remove deletes only mcp dot jdx and drops the emptied mcp object`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)
        service.run(kiloProjectRequest())

        val outcome = service.run(kiloProjectRequest(remove = true))

        (outcome as SetupService.SetupOutcome.Removed).changed shouldBe true
        val stored = Json.parseToJsonElement(
            Files.readString(dirs.project.resolve(".kilo/kilo.json")),
        ).jsonObject
        stored.containsKey("mcp") shouldBe false
        SetupService.isInstalledAt(dirs.project.resolve(".kilo/kilo.json"), SetupService.Agent.KILO) shouldBe false

        val again = service.run(kiloProjectRequest(remove = true))
        (again as SetupService.SetupOutcome.Removed).changed shouldBe false
        setupExitCode(again) shouldBe 0
    }

    @Test
    fun `kilo remove leaves other servers and any servers key untouched`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve("kilo.json"),
            """{"mcp":{"other":{"type":"local","command":["other"]},"servers":{"type":"local","command":["mine"]}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)
        service.run(kiloProjectRequest())

        val outcome = service.run(kiloProjectRequest(remove = true))

        (outcome as SetupService.SetupOutcome.Removed).changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(dirs.project.resolve("kilo.json"))).jsonObject
        stored["mcp"]?.jsonObject?.containsKey("jdx") shouldBe false
        stored["mcp"]?.jsonObject?.containsKey("other") shouldBe true
        stored["mcp"]?.jsonObject?.get("servers")?.jsonObject
            ?.get("command")?.toString() shouldBe """["mine"]"""
    }

    @Test
    fun `a second kilo install is a byte-identical no-op`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val first = service.run(kiloProjectRequest()) as SetupService.SetupOutcome.Installed
        val before = Files.readAllBytes(first.path)
        val second = service.run(kiloProjectRequest())

        (second as SetupService.SetupOutcome.Installed).changed shouldBe false
        Files.readAllBytes(first.path) shouldBe before
    }

    @Test
    fun `kilo check reports without writing`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val absent = service.run(kiloProjectRequest(check = true))
        absent as SetupService.SetupOutcome.Checked
        absent.installed shouldBe false
        setupExitCode(absent) shouldBe 1
        Files.exists(dirs.project.resolve(".kilo/kilo.json")) shouldBe false

        service.run(kiloProjectRequest())
        val present = service.run(kiloProjectRequest(check = true))
        present as SetupService.SetupOutcome.Checked
        present.installed shouldBe true
        setupExitCode(present) shouldBe 0
    }

    @Test
    fun `kilo backends do not cross-wire with opencode configs`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)
        service.run(kiloProjectRequest())
        val kiloPath = dirs.project.resolve(".kilo/kilo.json")

        // The Kilo file wires Kilo and also satisfies the lenient OpenCode probe (shared v1 shape).
        SetupService.isInstalledAt(kiloPath, SetupService.Agent.KILO) shouldBe true
        // A v2-only fragment wires OpenCode but never Kilo.
        val v2Only = Json.parseToJsonElement(
            """{"mcp":{"servers":{"jdx":{"type":"local","command":["jdx","mcp"],"disabled":false}}}}""",
        ).jsonObject
        SetupService.isInstalledRootFor(SetupService.Agent.OPENCODE, v2Only) shouldBe true
        SetupService.isInstalledRootFor(SetupService.Agent.KILO, v2Only) shouldBe false
    }

    // -- OpenCode v1 vs v2 detection: which line is installed for use --

    @Test
    fun `version parsing names each documented line and never guesses`() {
        SetupService.parseOpencodeVersion("opencode 1.18.3") shouldBe SetupService.OpencodeVersion.V1
        SetupService.parseOpencodeVersion("opencode 1.18.18") shouldBe SetupService.OpencodeVersion.V1
        SetupService.parseOpencodeVersion("opencode v1.2.3") shouldBe SetupService.OpencodeVersion.V1
        SetupService.parseOpencodeVersion("opencode2 v0.0.0-next-15806") shouldBe SetupService.OpencodeVersion.V2
        SetupService.parseOpencodeVersion("opencode v0.0.0-next-17403") shouldBe SetupService.OpencodeVersion.V2
        SetupService.parseOpencodeVersion("opencode v0.0.0-beta-18414") shouldBe SetupService.OpencodeVersion.V2
        SetupService.parseOpencodeVersion("opencode v0.0.0-dev-17643") shouldBe SetupService.OpencodeVersion.V2
        SetupService.parseOpencodeVersion("opencode 2.0.0") shouldBe SetupService.OpencodeVersion.V2
        SetupService.parseOpencodeVersion(null) shouldBe SetupService.OpencodeVersion.UNKNOWN
        SetupService.parseOpencodeVersion("") shouldBe SetupService.OpencodeVersion.UNKNOWN
        SetupService.parseOpencodeVersion("banana") shouldBe SetupService.OpencodeVersion.UNKNOWN
        SetupService.parseOpencodeVersion("opencode") shouldBe SetupService.OpencodeVersion.UNKNOWN
        SetupService.parseOpencodeVersion("version two") shouldBe SetupService.OpencodeVersion.UNKNOWN
        SetupService.parseOpencodeVersion("opencode 0.0.0") shouldBe SetupService.OpencodeVersion.UNKNOWN
        SetupService.parseOpencodeVersion("opencode 26.0.2") shouldBe SetupService.OpencodeVersion.UNKNOWN
    }

    @Test
    fun `version parsing over generated v1 and v2 outputs`(): Unit = runBlocking {
        checkAll(Arb.int(0..50), Arb.int(0..50)) { minor, patch ->
            SetupService.parseOpencodeVersion("opencode 1.$minor.$patch") shouldBe SetupService.OpencodeVersion.V1
        }
        checkAll(Arb.int(10000..20000)) { build ->
            SetupService.parseOpencodeVersion("opencode v0.0.0-next-$build") shouldBe SetupService.OpencodeVersion.V2
            SetupService.parseOpencodeVersion("opencode v0.0.0-beta-$build") shouldBe SetupService.OpencodeVersion.V2
        }
    }

    @Test
    fun `describeVersion names the line and the raw output`(): Unit = runBlocking {
        checkAll(Arb.int(0..50), Arb.int(0..50)) { minor, patch ->
            // The described line is stable over generated version numbers.
            val raw = "opencode 1.$minor.$patch"
            SetupService.describeVersion(
                SetupService.OpencodeVersionInfo(SetupService.OpencodeVersion.V1, raw, "opencode"),
            ) shouldBe "opencode v1 ($raw)"
        }
    }

    @Test
    fun `describeVersion covers every variant`() {
        SetupService.describeVersion(
            SetupService.OpencodeVersionInfo(SetupService.OpencodeVersion.V2, "opencode v0.0.0-next-1", "opencode"),
        ) shouldBe "opencode v2 (opencode v0.0.0-next-1)"
        SetupService.describeVersion(
            SetupService.OpencodeVersionInfo(SetupService.OpencodeVersion.ABSENT),
        ) shouldBe "opencode not found on PATH"
        SetupService.describeVersion(
            SetupService.OpencodeVersionInfo(SetupService.OpencodeVersion.UNKNOWN, "banana", "opencode"),
        ) shouldBe "opencode version unknown (banana)"
        SetupService.describeVersion(
            SetupService.OpencodeVersionInfo(SetupService.OpencodeVersion.UNKNOWN),
        ) shouldBe "opencode version unknown"
    }

    private fun executableBin(root: Path, vararg names: String): Path {
        val bin = root.resolve("bin").also { Files.createDirectories(it) }
        for (name in names) {
            Files.createFile(bin.resolve(name)).toFile().setExecutable(true)
        }
        return bin
    }

    @Test
    fun `probe classifies the opencode binary on PATH`(@TempDir root: Path) {
        val bin = executableBin(root, "opencode")
        val runner = ProcessRunner { _, _ -> ProcessOutcome(0, "opencode 1.18.3", "") }

        val info = SetupService.probeOpencodeVersion(listOf(bin), runner)

        info.version shouldBe SetupService.OpencodeVersion.V1
        info.raw shouldBe "opencode 1.18.3"
        info.binary shouldBe "opencode"
    }

    @Test
    fun `probe falls back to the opencode2 shim only when opencode is absent`(@TempDir root: Path) {
        val bin = executableBin(root, "opencode2")
        val runner = ProcessRunner { _, _ -> ProcessOutcome(0, "opencode2 v0.0.0-next-15806", "") }

        val info = SetupService.probeOpencodeVersion(listOf(bin), runner)

        info.version shouldBe SetupService.OpencodeVersion.V2
        info.binary shouldBe "opencode2"
    }

    @Test
    fun `probe prefers opencode over a legacy opencode2 shim`() {
        runBlocking {
            checkAll(50, Arb.string(0..20)) { noise ->
                val clean = noise.replace(Regex("[\\p{Cntrl}]"), " ").replace("\"", "'")
                val root = Files.createTempDirectory("jdx-opencode-probe-")
                val bin = executableBin(root, "opencode", "opencode2")
                // Whatever opencode answers wins — even garbage (UNKNOWN, never the shim's line).
                val runner = ProcessRunner { executable, _ ->
                    // Extension-aware: on Windows the probe resolves
                    // opencode.exe (see toolFileNames); the stem still
                    // distinguishes opencode from the opencode2 shim.
                    if (executable.fileName.toString().substringBefore(".") == "opencode") {
                        ProcessOutcome(0, "opencode 1.9.0 $clean", "")
                    } else {
                        ProcessOutcome(0, "opencode2 v0.0.0-next-15806", "")
                    }
                }

                val info = SetupService.probeOpencodeVersion(listOf(bin), runner)

                info.version shouldBe SetupService.OpencodeVersion.V1
                info.binary shouldBe "opencode"
            }
        }
    }

    @Test
    fun `probe is absent without binaries and unknown when the run fails`(@TempDir root: Path) {
        val bin = root.resolve("empty-bin").also { Files.createDirectories(it) }
        val runner = ProcessRunner { _, _ -> ProcessOutcome(0, "opencode 1.18.3", "") }

        SetupService.probeOpencodeVersion(listOf(bin), runner).version shouldBe
            SetupService.OpencodeVersion.ABSENT

        val withBinary = executableBin(root, "opencode")
        SetupService.probeOpencodeVersion(listOf(withBinary), throwingRunner("boom")).version shouldBe
            SetupService.OpencodeVersion.UNKNOWN
        SetupService.probeOpencodeVersion(
            listOf(withBinary),
            ProcessRunner { _, _ -> ProcessOutcome(0, "banana", "") },
        ).version shouldBe SetupService.OpencodeVersion.UNKNOWN
    }

    // -- generating family: the JSONC stripper never corrupts string payloads --

    @Test
    fun `stripping keeps comment-like text inside strings`(): Unit = runBlocking {
        checkAll(200, Arb.string()) { payload ->
            val sanitized = payload.replace(Regex("[\\p{Cntrl}]"), " ")
            val embedded = sanitized
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            val stripped = SetupService.stripJsonc("""{"k": "$embedded"} // trailing""")
            val parsed = Json.parseToJsonElement(stripped).jsonObject
            parsed["k"]?.jsonPrimitive?.content shouldBe sanitized
        }
    }

    @Test
    fun `stripping removes full-line and trailing comments deterministically`(): Unit = runBlocking {
        checkAll(Arb.string(0..20)) { comment ->
            // A terminator inside a block comment ends it by construction, so
            // generated payloads neutralise `*/` before embedding (L-026).
            val clean = comment.replace(Regex("[\\p{Cntrl}]"), " ").replace("\"", "'").replace("*/", "* /")
            val text = "// $clean\n{\"a\": 1 /* $clean */, \"b\": [1, 2,],}"
            val parsed = Json.parseToJsonElement(SetupService.stripJsonc(text)).jsonObject
            parsed["a"]?.jsonPrimitive?.content shouldBe "1"
            parsed["b"].toString() shouldBe "[1,2]"
            SetupService.stripJsonc(text) shouldBe SetupService.stripJsonc(text)
        }
    }

    // -- Claude Code backend (.mcp.json / ~/.claude.json, mcpServers.jdx) --

    private fun claudeProjectRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.CLAUDE_CODE,
        scope = SetupService.Scope.PROJECT,
        check = check,
        remove = remove,
    )

    private fun claudeSystemRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.CLAUDE_CODE,
        scope = SetupService.Scope.SYSTEM,
        check = check,
        remove = remove,
    )

    @Test
    fun `a fresh claude project install creates dot mcp json with the jdx entry`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(claudeProjectRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.changed shouldBe true
        installed.path shouldBe dirs.project.resolve(".mcp.json")
        setupExitCode(outcome) shouldBe 0
        val stored = Json.parseToJsonElement(Files.readString(installed.path)).jsonObject
        val entry = stored["mcpServers"]?.jsonObject?.get("jdx")?.jsonObject
        entry?.get("command")?.jsonPrimitive?.content shouldBe "jdx"
        entry?.get("args").toString() shouldBe """["mcp"]"""
        SetupService.isInstalledRoot(stored, SetupService.Agent.CLAUDE_CODE) shouldBe true
        SetupService.isInstalledAt(installed.path, SetupService.Agent.CLAUDE_CODE) shouldBe true
        // OpenCode probe does not mistake a Claude file for wired.
        SetupService.isInstalledAt(installed.path) shouldBe false
    }

    @Test
    fun `claude install preserves other servers and never clobbers`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve(".mcp.json"),
            """{"mcpServers":{"other":{"command":"other","args":["x"]}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(claudeProjectRequest()) as SetupService.SetupOutcome.Installed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(outcome.path)).jsonObject
        stored["mcpServers"]?.jsonObject?.get("other")?.jsonObject
            ?.get("command")?.jsonPrimitive?.content shouldBe "other"
        SetupService.isInstalledRoot(stored, SetupService.Agent.CLAUDE_CODE) shouldBe true
    }

    @Test
    fun `a second claude install is a byte-identical no-op`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val first = service.run(claudeProjectRequest()) as SetupService.SetupOutcome.Installed
        val before = Files.readAllBytes(first.path)
        val second = service.run(claudeProjectRequest())

        (second as SetupService.SetupOutcome.Installed).changed shouldBe false
        Files.readAllBytes(first.path) shouldBe before
    }

    @Test
    fun `claude check reports without writing`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val absent = service.run(claudeProjectRequest(check = true))
        absent as SetupService.SetupOutcome.Checked
        absent.installed shouldBe false
        setupExitCode(absent) shouldBe 1
        Files.exists(dirs.project.resolve(".mcp.json")) shouldBe false

        service.run(claudeProjectRequest())
        val present = service.run(claudeProjectRequest(check = true))
        present as SetupService.SetupOutcome.Checked
        present.installed shouldBe true
        setupExitCode(present) shouldBe 0
    }

    @Test
    fun `claude remove deletes only the jdx entry and drops the emptied namespace`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)
        service.run(claudeProjectRequest())

        val outcome = service.run(claudeProjectRequest(remove = true)) as SetupService.SetupOutcome.Removed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(dirs.project.resolve(".mcp.json"))).jsonObject
        stored.containsKey("mcpServers") shouldBe false
        SetupService.isInstalledAt(dirs.project.resolve(".mcp.json"), SetupService.Agent.CLAUDE_CODE) shouldBe false

        val again = service.run(claudeProjectRequest(remove = true))
        (again as SetupService.SetupOutcome.Removed).changed shouldBe false
        setupExitCode(again) shouldBe 0
    }

    @Test
    fun `claude remove keeps other servers`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve(".mcp.json"),
            """{"mcpServers":{"other":{"command":"other","args":["x"]}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)
        service.run(claudeProjectRequest())

        val outcome = service.run(claudeProjectRequest(remove = true)) as SetupService.SetupOutcome.Removed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(dirs.project.resolve(".mcp.json"))).jsonObject
        stored["mcpServers"]?.jsonObject?.containsKey("jdx") shouldBe false
        stored["mcpServers"]?.jsonObject?.containsKey("other") shouldBe true
    }

    @Test
    fun `claude system scope targets dot claude json under the fake home`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val created = service.run(claudeSystemRequest()) as SetupService.SetupOutcome.Installed

        created.path shouldBe dirs.home.resolve(".claude.json")
        SetupService.isInstalledAt(created.path, SetupService.Agent.CLAUDE_CODE) shouldBe true
    }

    @Test
    fun `claude project scope walks up to the nearest mcp json`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(dirs.project.resolve(".mcp.json"), "{}")
        val nested = dirs.project.resolve("a/b").also { Files.createDirectories(it) }
        val service = SetupService(dirs.home, nested)

        val outcome = service.run(claudeProjectRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.path shouldBe dirs.project.resolve(".mcp.json")
        Files.exists(nested.resolve(".mcp.json")) shouldBe false
    }

    @Test
    fun `a claude corrupt config exits 5 and names the file`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(dirs.project.resolve(".mcp.json"), "{ not json,")
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(claudeProjectRequest())

        outcome as SetupService.SetupOutcome.Corrupt
        setupExitCode(outcome) shouldBe 5
    }

    @Test
    fun `claude entry probe accepts absolute install paths and rejects foreign commands`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve(".mcp.json"),
            """{"mcpServers":{"jdx":{"command":"/home/u/.local/bin/jdx","args":["mcp"]}}}""",
        )

        SetupService.isInstalledAt(
            dirs.project.resolve(".mcp.json"),
            SetupService.Agent.CLAUDE_CODE,
        ) shouldBe true

        Files.writeString(
            dirs.project.resolve(".mcp.json"),
            """{"mcpServers":{"jdx":{"command":"other","args":["mcp"]}}}""",
        )
        SetupService.isInstalledAt(
            dirs.project.resolve(".mcp.json"),
            SetupService.Agent.CLAUDE_CODE,
        ) shouldBe false

        Files.writeString(
            dirs.project.resolve(".mcp.json"),
            """{"mcpServers":{"jdx":{"command":"jdx","args":["other"]}}}""",
        )
        SetupService.isInstalledAt(
            dirs.project.resolve(".mcp.json"),
            SetupService.Agent.CLAUDE_CODE,
        ) shouldBe false
    }

    @Test
    fun `claude entry probe accepts windows shims and backslash paths`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val path = dirs.project.resolve(".mcp.json")
        for (command in listOf("jdx.exe", "jdx.cmd", "jdx.bat", "JDX.EXE", "C:\\tools\\jdx.exe", "C:/tools/jdx.cmd")) {
            Files.writeString(path, claudeRootWithCommand(command).toString())
            SetupService.isInstalledAt(path, SetupService.Agent.CLAUDE_CODE) shouldBe true
        }
        Files.writeString(path, claudeRootWithCommand("jdx.exe", arg = "other").toString())
        SetupService.isInstalledAt(path, SetupService.Agent.CLAUDE_CODE) shouldBe false
    }

    private fun claudeRootWithCommand(command: String, arg: String = "mcp"): JsonObject = buildJsonObject {
        put(
            "mcpServers",
            buildJsonObject {
                put(
                    "jdx",
                    buildJsonObject {
                        put("command", command)
                        put("args", JsonArray(listOf(JsonPrimitive(arg))))
                    },
                )
            },
        )
    }

    @Test
    fun `opencode entry probe accepts windows shims and backslash paths`() {
        for (command in listOf("jdx", "jdx.exe", "jdx.bat", "C:\\tools\\jdx.exe", "/home/u/.local/bin/jdx")) {
            val entry = buildJsonObject {
                put("type", "local")
                put("command", JsonArray(listOf(JsonPrimitive(command), JsonPrimitive("mcp"))))
                put("enabled", true)
            }
            SetupService.isJdxEntry(entry) shouldBe true
        }
        val foreign = buildJsonObject {
            put("type", "local")
            put("command", JsonArray(listOf(JsonPrimitive("other.exe"), JsonPrimitive("mcp"))))
            put("enabled", true)
        }
        SetupService.isJdxEntry(foreign) shouldBe false
    }

    @Test
    fun `a hostile claude command word never throws the installed probe`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve(".mcp.json"),
            "{\"mcpServers\":{\"jdx\":{\"command\":\"\u0000\",\"args\":[\"mcp\"]}}}",
        )

        SetupService.isInstalledAt(
            dirs.project.resolve(".mcp.json"),
            SetupService.Agent.CLAUDE_CODE,
        ) shouldBe false
    }

    @Test
    fun `targetPath resolves per agent and scope`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        service.targetPath(SetupService.Agent.OPENCODE, SetupService.Scope.PROJECT) shouldBe
            dirs.project.resolve("opencode.json")
        service.targetPath(SetupService.Agent.OPENCODE, SetupService.Scope.SYSTEM) shouldBe
            dirs.home.resolve(".config/opencode/opencode.json")
        service.targetPath(SetupService.Agent.CLAUDE_CODE, SetupService.Scope.PROJECT) shouldBe
            dirs.project.resolve(".mcp.json")
        service.targetPath(SetupService.Agent.CLAUDE_CODE, SetupService.Scope.SYSTEM) shouldBe
            dirs.home.resolve(".claude.json")
        service.targetPath(SetupService.Agent.CURSOR, SetupService.Scope.PROJECT) shouldBe
            dirs.project.resolve(".cursor/mcp.json")
        service.targetPath(SetupService.Agent.CURSOR, SetupService.Scope.SYSTEM) shouldBe
            dirs.home.resolve(".cursor/mcp.json")
    }

    // -- Cursor backend (.cursor/mcp.json / ~/.cursor/mcp.json, mcpServers.jdx) --
    // Verified against a real install: `cursor-agent 2026.09.26-dd393fe`
    // (`agent mcp list` reads `.cursor/mcp.json` in the cwd and
    // `~/.cursor/mcp.json` globally; `agent mcp --help` names both files).

    private fun cursorProjectRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.CURSOR,
        scope = SetupService.Scope.PROJECT,
        check = check,
        remove = remove,
    )

    private fun cursorSystemRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.CURSOR,
        scope = SetupService.Scope.SYSTEM,
        check = check,
        remove = remove,
    )

    @Test
    fun `parseAgent covers cursor spellings`() {
        SetupService.parseAgent("cursor") shouldBe SetupService.Agent.CURSOR
        SetupService.parseAgent("Cursor") shouldBe SetupService.Agent.CURSOR
        SetupService.parseAgent("cursor-code") shouldBe SetupService.Agent.CURSOR
        SetupService.parseAgent("cursorcode") shouldBe SetupService.Agent.CURSOR
        SetupService.parseAgent("CURSOR_CODE") shouldBe SetupService.Agent.CURSOR
    }

    @Test
    fun `a fresh cursor project install creates dot-cursor mcp json with the jdx entry`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(cursorProjectRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.changed shouldBe true
        installed.path shouldBe dirs.project.resolve(".cursor/mcp.json")
        setupExitCode(outcome) shouldBe 0
        val stored = Json.parseToJsonElement(Files.readString(installed.path)).jsonObject
        val entry = stored["mcpServers"]?.jsonObject?.get("jdx")?.jsonObject
        entry?.get("command")?.jsonPrimitive?.content shouldBe "jdx"
        entry?.get("args").toString() shouldBe """["mcp"]"""
        SetupService.isInstalledRoot(stored, SetupService.Agent.CURSOR) shouldBe true
        SetupService.isInstalledAt(installed.path, SetupService.Agent.CURSOR) shouldBe true
        // The Claude probe shares the shape but each agent checks its own file;
        // a Cursor file is wired for Cursor here.
        SetupService.isInstalledRoot(stored, SetupService.Agent.CLAUDE_CODE) shouldBe true
    }

    @Test
    fun `cursor install preserves other servers and never clobbers`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.createDirectories(dirs.project.resolve(".cursor"))
        Files.writeString(
            dirs.project.resolve(".cursor/mcp.json"),
            """{"mcpServers":{"other":{"command":"other","args":["x"]}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(cursorProjectRequest()) as SetupService.SetupOutcome.Installed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(outcome.path)).jsonObject
        stored["mcpServers"]?.jsonObject?.get("other")?.jsonObject
            ?.get("command")?.jsonPrimitive?.content shouldBe "other"
        SetupService.isInstalledRoot(stored, SetupService.Agent.CURSOR) shouldBe true
    }

    @Test
    fun `a second cursor install is a byte-identical no-op`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val first = service.run(cursorProjectRequest()) as SetupService.SetupOutcome.Installed
        val before = Files.readAllBytes(first.path)
        val second = service.run(cursorProjectRequest())

        (second as SetupService.SetupOutcome.Installed).changed shouldBe false
        Files.readAllBytes(first.path) shouldBe before
    }

    @Test
    fun `cursor check reports without writing`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val absent = service.run(cursorProjectRequest(check = true))
        absent as SetupService.SetupOutcome.Checked
        absent.installed shouldBe false
        setupExitCode(absent) shouldBe 1
        Files.exists(dirs.project.resolve(".cursor/mcp.json")) shouldBe false

        service.run(cursorProjectRequest())
        val present = service.run(cursorProjectRequest(check = true))
        present as SetupService.SetupOutcome.Checked
        present.installed shouldBe true
        setupExitCode(present) shouldBe 0
    }

    @Test
    fun `cursor remove deletes only the jdx entry and drops the emptied namespace`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)
        service.run(cursorProjectRequest())

        val outcome = service.run(cursorProjectRequest(remove = true)) as SetupService.SetupOutcome.Removed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(dirs.project.resolve(".cursor/mcp.json"))).jsonObject
        stored.containsKey("mcpServers") shouldBe false
        SetupService.isInstalledAt(dirs.project.resolve(".cursor/mcp.json"), SetupService.Agent.CURSOR) shouldBe false

        val again = service.run(cursorProjectRequest(remove = true))
        (again as SetupService.SetupOutcome.Removed).changed shouldBe false
        setupExitCode(again) shouldBe 0
    }

    @Test
    fun `cursor remove keeps other servers`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.createDirectories(dirs.project.resolve(".cursor"))
        Files.writeString(
            dirs.project.resolve(".cursor/mcp.json"),
            """{"mcpServers":{"other":{"command":"other","args":["x"]}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)
        service.run(cursorProjectRequest())

        val outcome = service.run(cursorProjectRequest(remove = true)) as SetupService.SetupOutcome.Removed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(dirs.project.resolve(".cursor/mcp.json"))).jsonObject
        stored["mcpServers"]?.jsonObject?.containsKey("jdx") shouldBe false
        stored["mcpServers"]?.jsonObject?.containsKey("other") shouldBe true
    }

    @Test
    fun `cursor system scope targets dot-cursor mcp json under the fake home`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val created = service.run(cursorSystemRequest()) as SetupService.SetupOutcome.Installed

        created.path shouldBe dirs.home.resolve(".cursor/mcp.json")
        SetupService.isInstalledAt(created.path, SetupService.Agent.CURSOR) shouldBe true
    }

    @Test
    fun `cursor project scope walks up to the nearest dot-cursor mcp json`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.createDirectories(dirs.project.resolve(".cursor"))
        Files.writeString(dirs.project.resolve(".cursor/mcp.json"), "{}")
        val nested = dirs.project.resolve("a/b").also { Files.createDirectories(it) }
        val service = SetupService(dirs.home, nested)

        val outcome = service.run(cursorProjectRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.path shouldBe dirs.project.resolve(".cursor/mcp.json")
        Files.exists(nested.resolve(".cursor/mcp.json")) shouldBe false
    }

    @Test
    fun `a cursor corrupt config exits 5 and names the file`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.createDirectories(dirs.project.resolve(".cursor"))
        Files.writeString(dirs.project.resolve(".cursor/mcp.json"), "{ not json,")
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(cursorProjectRequest())

        outcome as SetupService.SetupOutcome.Corrupt
        setupExitCode(outcome) shouldBe 5
    }

    @Test
    fun `cursor version probe prefers cursor-agent over the agent shim`(@TempDir root: Path) {
        val bin = root.resolve("bin").also { Files.createDirectories(it) }
        Files.createFile(bin.resolve("cursor-agent")).toFile().setExecutable(true)
        Files.createFile(bin.resolve("agent")).toFile().setExecutable(true)
        val seen = mutableListOf<String>()
        val runner = ProcessRunner { executable, _ ->
            seen.add(executable.fileName.toString())
            ProcessOutcome(0, "2026.09.26-dd393fe", "")
        }

        val info = SetupService.probeCursorVersion(listOf(bin), runner)

        info.version shouldBe SetupService.CursorVersion.PRESENT
        info.raw shouldBe "2026.09.26-dd393fe"
        info.binary shouldBe "cursor-agent"
        seen shouldBe listOf("cursor-agent")
    }

    @Test
    fun `cursor version probe falls back to the agent shim`() {
        val info = SetupService.probeCursorVersion(
            emptyList(),
            ProcessRunner { _, _ -> ProcessOutcome(0, "x", "") },
        )
        // No binaries on the fake PATH: absent, never a guess.
        info.version shouldBe SetupService.CursorVersion.ABSENT
    }

    @Test
    fun `cursor version probe classifies answers`(@TempDir root: Path) {
        val bin = root.resolve("bin").also { Files.createDirectories(it) }
        Files.createFile(bin.resolve("cursor-agent")).toFile().setExecutable(true)

        val present = SetupService.probeCursorVersion(
            listOf(bin),
            ProcessRunner { _, _ -> ProcessOutcome(0, "2026.09.26-dd393fe", "") },
        )
        present.version shouldBe SetupService.CursorVersion.PRESENT
        present.raw shouldBe "2026.09.26-dd393fe"
        present.binary shouldBe "cursor-agent"

        val blank = SetupService.probeCursorVersion(
            listOf(bin),
            ProcessRunner { _, _ -> ProcessOutcome(0, "  ", "") },
        )
        blank.version shouldBe SetupService.CursorVersion.UNKNOWN

        val failing = SetupService.probeCursorVersion(
            listOf(bin),
            throwingRunner("boom"),
        )
        failing.version shouldBe SetupService.CursorVersion.UNKNOWN
    }

    @Test
    fun `cursor probe resolves the exe shim on windows`(@TempDir root: Path) {
        val bin = root.resolve("bin").also { Files.createDirectories(it) }
        Files.createFile(bin.resolve("cursor-agent.exe")).toFile().setExecutable(true)
        val runner = ProcessRunner { _, _ -> ProcessOutcome(0, "2026.09.26-dd393fe", "") }

        val info = SetupService.probeCursorVersion(listOf(bin), runner, osName = "Windows 11")

        info.version shouldBe SetupService.CursorVersion.PRESENT
        info.raw shouldBe "2026.09.26-dd393fe"
        info.binary shouldBe "cursor-agent"
    }

    @Test
    fun `describeCursorVersion covers every variant`() {
        SetupService.describeCursorVersion(
            SetupService.CursorVersionInfo(SetupService.CursorVersion.PRESENT, "2026.09.26-dd393fe", "cursor-agent"),
        ) shouldBe "cursor (2026.09.26-dd393fe)"
        SetupService.describeCursorVersion(
            SetupService.CursorVersionInfo(SetupService.CursorVersion.ABSENT),
        ) shouldBe "cursor not found on PATH"
        SetupService.describeCursorVersion(
            SetupService.CursorVersionInfo(SetupService.CursorVersion.UNKNOWN, "banana", "cursor-agent"),
        ) shouldBe "cursor version unknown (banana)"
        SetupService.describeCursorVersion(
            SetupService.CursorVersionInfo(SetupService.CursorVersion.UNKNOWN),
        ) shouldBe "cursor version unknown"
    }

    @Test
    fun `claude version probing names presence without guessing`() {
        SetupService.probeClaudeVersion(
            emptyList(),
            ProcessRunner { _, _ -> ProcessOutcome(0, "x", "") },
        ).version shouldBe SetupService.ClaudeVersion.ABSENT
    }

    @Test
    fun `claude version probe classifies answers`(@TempDir root: Path) {
        val bin = root.resolve("bin").also { Files.createDirectories(it) }
        Files.createFile(bin.resolve("claude")).toFile().setExecutable(true)

        val present = SetupService.probeClaudeVersion(
            listOf(bin),
            ProcessRunner { _, _ -> ProcessOutcome(0, "1.0.33 (Claude Code)", "") },
        )
        present.version shouldBe SetupService.ClaudeVersion.PRESENT
        present.raw shouldBe "1.0.33 (Claude Code)"
        present.binary shouldBe "claude"

        val blank = SetupService.probeClaudeVersion(
            listOf(bin),
            ProcessRunner { _, _ -> ProcessOutcome(0, "  ", "") },
        )
        blank.version shouldBe SetupService.ClaudeVersion.UNKNOWN

        val failing = SetupService.probeClaudeVersion(
            listOf(bin),
            throwingRunner("boom"),
        )
        failing.version shouldBe SetupService.ClaudeVersion.UNKNOWN
    }

    @Test
    fun `claude probe resolves the exe shim on windows`(@TempDir root: Path) {
        val bin = root.resolve("bin").also { Files.createDirectories(it) }
        Files.createFile(bin.resolve("claude.exe")).toFile().setExecutable(true)
        val runner = ProcessRunner { _, _ -> ProcessOutcome(0, "1.0.33 (Claude Code)", "") }

        val info = SetupService.probeClaudeVersion(listOf(bin), runner, osName = "Windows 11")

        info.version shouldBe SetupService.ClaudeVersion.PRESENT
        info.raw shouldBe "1.0.33 (Claude Code)"
        info.binary shouldBe "claude"
    }

    @Test
    fun `describeClaudeVersion covers every variant`() {
        SetupService.describeClaudeVersion(
            SetupService.ClaudeVersionInfo(SetupService.ClaudeVersion.PRESENT, "1.0.33 (Claude Code)", "claude"),
        ) shouldBe "claude-code (1.0.33 (Claude Code))"
        SetupService.describeClaudeVersion(
            SetupService.ClaudeVersionInfo(SetupService.ClaudeVersion.ABSENT),
        ) shouldBe "claude-code not found on PATH"
        SetupService.describeClaudeVersion(
            SetupService.ClaudeVersionInfo(SetupService.ClaudeVersion.UNKNOWN, "banana", "claude"),
        ) shouldBe "claude-code version unknown (banana)"
        SetupService.describeClaudeVersion(
            SetupService.ClaudeVersionInfo(SetupService.ClaudeVersion.UNKNOWN),
        ) shouldBe "claude-code version unknown"
    }

    // -- Cline backend (single global cline_mcp_settings.json, nested transport entry) --

    private fun clineProjectRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.CLINE,
        scope = SetupService.Scope.PROJECT,
        check = check,
        remove = remove,
    )

    // -- Codex CLI backend (config.toml, [mcp_servers.jdx]) --

    private fun codexProjectRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.CODEX,
        scope = SetupService.Scope.PROJECT,
        check = check,
        remove = remove,
    )

    private fun clineSystemRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.CLINE,
        scope = SetupService.Scope.SYSTEM,
        check = check,
        remove = remove,
    )

    private fun codexSystemRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.CODEX,
        scope = SetupService.Scope.SYSTEM,
        check = check,
        remove = remove,
    )

    private fun clinePath(home: Path): Path = home.resolve(".cline/data/settings/cline_mcp_settings.json")

    @Test
    fun `a fresh cline system install writes the transport entry the real CLI writes`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(clineSystemRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.changed shouldBe true
        installed.path shouldBe clinePath(dirs.home)
        setupExitCode(outcome) shouldBe 0
        val stored = Json.parseToJsonElement(Files.readString(installed.path)).jsonObject
        // No `$schema`, no flat command: exactly the `cline mcp add` shape.
        stored.containsKey("\$schema") shouldBe false
        val transport = stored["mcpServers"]?.jsonObject?.get("jdx")?.jsonObject?.get("transport")?.jsonObject
        transport?.get("type")?.jsonPrimitive?.content shouldBe "stdio"
        transport?.get("command")?.jsonPrimitive?.content shouldBe "jdx"
        transport?.get("args").toString() shouldBe """["mcp"]"""
        SetupService.isInstalledRoot(stored, SetupService.Agent.CLINE) shouldBe true
        SetupService.isInstalledAt(installed.path, SetupService.Agent.CLINE) shouldBe true
    }

    @Test
    fun `cline project scope targets the same single global file`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        // Cline keeps no project-level MCP file (verified against cline
        // 3.0.65 + extension 4.1.21): both scopes resolve to the global file.
        service.targetPath(SetupService.Agent.CLINE, SetupService.Scope.PROJECT) shouldBe
            clinePath(dirs.home)
        service.targetPath(SetupService.Agent.CLINE, SetupService.Scope.SYSTEM) shouldBe
            clinePath(dirs.home)

        val outcome = service.run(clineProjectRequest()) as SetupService.SetupOutcome.Installed

        outcome.path shouldBe clinePath(dirs.home)
        Files.exists(dirs.project.resolve(".cline/mcp.json")) shouldBe false
        SetupService.isInstalledAt(outcome.path, SetupService.Agent.CLINE) shouldBe true
    }

    @Test
    fun `cline install merges and never clobbers unrelated entries`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.createDirectories(clinePath(dirs.home).parent)
        Files.writeString(
            clinePath(dirs.home),
            """{"mcpServers":{"other":{"transport":{"type":"stdio","command":"other","args":["x"]}},"disabled-other":{"command":"other","args":["x"],"disabled":true}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(clineSystemRequest()) as SetupService.SetupOutcome.Installed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(outcome.path)).jsonObject
        stored["mcpServers"]?.jsonObject?.get("other")?.jsonObject
            ?.get("transport")?.jsonObject?.get("command")?.jsonPrimitive?.content shouldBe "other"
        stored["mcpServers"]?.jsonObject?.get("disabled-other")?.jsonObject
            ?.get("disabled").toString() shouldBe "true"
        SetupService.isInstalledRoot(stored, SetupService.Agent.CLINE) shouldBe true
    }

    @Test
    fun `a second cline install is a byte-identical no-op`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val first = service.run(clineSystemRequest()) as SetupService.SetupOutcome.Installed
        val before = Files.readAllBytes(first.path)
        val second = service.run(clineSystemRequest())

        (second as SetupService.SetupOutcome.Installed).changed shouldBe false
        Files.readAllBytes(first.path) shouldBe before
    }

    @Test
    fun `cline check reports without writing`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val absent = service.run(clineSystemRequest(check = true))
        absent as SetupService.SetupOutcome.Checked
        absent.installed shouldBe false
        setupExitCode(absent) shouldBe 1
        Files.exists(clinePath(dirs.home)) shouldBe false

        service.run(clineSystemRequest())
        val present = service.run(clineSystemRequest(check = true))
        present as SetupService.SetupOutcome.Checked
        present.installed shouldBe true
        setupExitCode(present) shouldBe 0
    }

    @Test
    fun `cline remove deletes only the jdx entry and drops the emptied namespace`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)
        service.run(clineSystemRequest())

        val outcome = service.run(clineSystemRequest(remove = true)) as SetupService.SetupOutcome.Removed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(clinePath(dirs.home))).jsonObject
        stored.containsKey("mcpServers") shouldBe false
        SetupService.isInstalledAt(clinePath(dirs.home), SetupService.Agent.CLINE) shouldBe false

        val again = service.run(clineSystemRequest(remove = true))
        (again as SetupService.SetupOutcome.Removed).changed shouldBe false
        setupExitCode(again) shouldBe 0
    }

    @Test
    fun `cline remove keeps other servers`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.createDirectories(clinePath(dirs.home).parent)
        Files.writeString(
            clinePath(dirs.home),
            """{"mcpServers":{"other":{"transport":{"type":"stdio","command":"other","args":["x"]}}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)
        service.run(clineSystemRequest())

        val outcome = service.run(clineSystemRequest(remove = true)) as SetupService.SetupOutcome.Removed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(clinePath(dirs.home))).jsonObject
        stored["mcpServers"]?.jsonObject?.containsKey("jdx") shouldBe false
        stored["mcpServers"]?.jsonObject?.containsKey("other") shouldBe true
    }

    @Test
    fun `a cline corrupt config exits 5 and names the file`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.createDirectories(clinePath(dirs.home).parent)
        Files.writeString(clinePath(dirs.home), "{ not json,")
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(clineSystemRequest())

        outcome as SetupService.SetupOutcome.Corrupt
        setupExitCode(outcome) shouldBe 5
    }

    @Test
    fun `cline entry probe accepts the legacy flat shape the binary still reads`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val path = clinePath(dirs.home)
        Files.createDirectories(path.parent)
        // Flat legacy shape from the Cline docs (no `transport` wrapper).
        Files.writeString(path, """{"mcpServers":{"jdx":{"command":"jdx","args":["mcp"]}}}""")

        SetupService.isInstalledAt(path, SetupService.Agent.CLINE) shouldBe true
    }

    @Test
    fun `cline entry probe accepts absolute install paths and windows shims`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val path = clinePath(dirs.home)
        Files.createDirectories(path.parent)
        for (command in listOf("/home/u/.local/bin/jdx", "jdx.exe", "jdx.cmd", "JDX.BAT", "C:\\tools\\jdx.exe")) {
            Files.writeString(path, clineRootWithCommand(command).toString())
            SetupService.isInstalledAt(path, SetupService.Agent.CLINE) shouldBe true
        }
        // A disabled entry is still wired (presence is what `--check` reports).
        Files.writeString(
            path,
            """{"mcpServers":{"jdx":{"transport":{"type":"stdio","command":"jdx","args":["mcp"]},"disabled":true}}}""",
        )
        SetupService.isInstalledAt(path, SetupService.Agent.CLINE) shouldBe true
    }

    @Test
    fun `cline entry probe rejects remote shapes and foreign commands`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val path = clinePath(dirs.home)
        Files.createDirectories(path.parent)
        // Remote transports cannot launch a local `jdx` process.
        for (entry in listOf(
            """{"transport":{"type":"streamableHttp","url":"https://example.com/mcp"}}""",
            """{"transport":{"type":"sse","url":"https://example.com/mcp"}}""",
            """{"type":"streamableHttp","url":"https://example.com/mcp"}""",
            """{"transport":{"type":"stdio","command":"other","args":["mcp"]}}""",
            """{"command":"other","args":["mcp"]}""",
            """{"transport":{"type":"stdio","command":"jdx","args":["other"]}}""",
            """{"transport":{"type":"stdio","command":"jdx"}}""",
        )) {
            Files.writeString(path, """{"mcpServers":{"jdx":$entry}}""")
            SetupService.isInstalledAt(path, SetupService.Agent.CLINE) shouldBe false
        }
    }

    private fun clineRootWithCommand(command: String): JsonObject = buildJsonObject {
        put(
            "mcpServers",
            buildJsonObject {
                put(
                    "jdx",
                    buildJsonObject {
                        put(
                            "transport",
                            buildJsonObject {
                                put("type", "stdio")
                                put("command", command)
                                put("args", JsonArray(listOf(JsonPrimitive("mcp"))))
                            },
                        )
                    },
                )
            },
        )
    }

    @Test
    fun `a hostile cline command word never throws the installed probe`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val path = clinePath(dirs.home)
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            "{\"mcpServers\":{\"jdx\":{\"transport\":{\"type\":\"stdio\",\"command\":\"\u0000\",\"args\":[\"mcp\"]}}}}",
        )

        SetupService.isInstalledAt(path, SetupService.Agent.CLINE) shouldBe false
    }

    @Test
    fun `cline agent parsing accepts documented spellings`() {
        SetupService.parseAgent("cline") shouldBe SetupService.Agent.CLINE
        SetupService.parseAgent("Cline") shouldBe SetupService.Agent.CLINE
        SetupService.parseAgent("CLINE") shouldBe SetupService.Agent.CLINE
        SetupService.parseAgent("cline-code") shouldBe SetupService.Agent.CLINE
        SetupService.parseAgent("clinecode") shouldBe SetupService.Agent.CLINE
        SetupService.parseAgent("CLINE_CODE") shouldBe SetupService.Agent.CLINE
    }

    @Test
    fun `cline merge preserves generated servers and stays idempotent`() {
        runBlocking {
            checkAll(Arb.string(1..24)) { raw ->
                // Server names must survive a JSON round-trip; control
                // characters and the owned name would skew the assertion.
                val name = raw.replace(Regex("[\\p{Cntrl}\"]"), " ").trim()
                    .ifEmpty { "other" }.takeUnless { it == "jdx" } ?: "other"
                val root = buildJsonObject {
                    put(
                        "mcpServers",
                        buildJsonObject {
                            put(
                                name,
                                buildJsonObject {
                                    put(
                                        "transport",
                                        buildJsonObject {
                                            put("type", "stdio")
                                            put("command", "other")
                                            put("args", JsonArray(listOf(JsonPrimitive("x"))))
                                        },
                                    )
                                },
                            )
                        },
                    )
                }
                val merged = SetupService.mergeInstallCline(root)
                // The foreign server survives the merge byte-free in shape.
                merged["mcpServers"]?.jsonObject?.get(name)?.jsonObject
                    ?.get("transport")?.jsonObject?.get("command")?.jsonPrimitive?.content shouldBe "other"
                SetupService.isInstalledRootFor(SetupService.Agent.CLINE, merged) shouldBe true
                // A second merge is a no-op: idempotence is structural.
                SetupService.mergeInstallCline(merged) shouldBe merged
                // Removing drops only the owned entry.
                val removed = SetupService.mergeRemoveCline(merged)
                removed["mcpServers"]?.jsonObject?.containsKey("jdx") shouldBe false
                removed["mcpServers"]?.jsonObject?.containsKey(name) shouldBe true
            }
        }
    }

    /** A service whose Codex system dir is pinned under the fake home (never the ambient `$CODEX_HOME`). */
    private fun codexService(dirs: FakeDirs): SetupService =
        SetupService(dirs.home, dirs.project, dirs.home.resolve(".codex"))

    @Test
    fun `parseAgent covers codex spellings`() {
        SetupService.parseAgent("codex") shouldBe SetupService.Agent.CODEX
        SetupService.parseAgent("Codex") shouldBe SetupService.Agent.CODEX
        SetupService.parseAgent("codex-cli") shouldBe SetupService.Agent.CODEX
        SetupService.parseAgent("codexcli") shouldBe SetupService.Agent.CODEX
        SetupService.parseAgent("CODEX_CLI") shouldBe SetupService.Agent.CODEX
    }

    @Test
    fun `a fresh codex project install writes the canonical table`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = codexService(dirs)

        val outcome = service.run(codexProjectRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.changed shouldBe true
        installed.path shouldBe dirs.project.resolve(".codex/config.toml")
        setupExitCode(outcome) shouldBe 0
        Files.readString(installed.path) shouldBe SetupService.desiredCodexBlock()
        SetupService.isInstalledAt(installed.path, SetupService.Agent.CODEX) shouldBe true
    }

    @Test
    fun `codex system scope honours the injected codex home`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project, root.resolve("custom-codex-home"))

        val outcome = service.run(codexSystemRequest()) as SetupService.SetupOutcome.Installed

        outcome.path shouldBe root.resolve("custom-codex-home/config.toml")
        SetupService.isInstalledAt(outcome.path, SetupService.Agent.CODEX) shouldBe true
    }

    @Test
    fun `codex system scope defaults under the user home`(@TempDir root: Path) {
        val dirs = fakeDirs(root)

        SetupService.codexSystemConfigPath(dirs.home, dirs.home.resolve(".codex")) shouldBe
            dirs.home.resolve(".codex/config.toml")
        SetupService.codexSystemConfigPath(dirs.home) shouldBe
            dirs.home.resolve(".codex/config.toml")
    }

    @Test
    fun `codex project scope walks up to the nearest dot-codex config`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val nested = dirs.project.resolve("a/b").also { Files.createDirectories(it) }
        Files.createDirectories(dirs.project.resolve(".codex"))
        Files.writeString(
            dirs.project.resolve(".codex/config.toml"),
            "[mcp_servers.other]\ncommand = \"other\"\nargs = [\"x\"]\n",
        )
        val service = SetupService(dirs.home, nested, dirs.home.resolve(".codex"))

        val outcome = service.run(codexProjectRequest()) as SetupService.SetupOutcome.Installed

        outcome.path shouldBe dirs.project.resolve(".codex/config.toml")
        val stored = Files.readString(outcome.path)
        stored shouldContain "[mcp_servers.other]"
        SetupService.isInstalledAt(outcome.path, SetupService.Agent.CODEX) shouldBe true
    }

    @Test
    fun `a second codex install is a byte-exact no-op`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = codexService(dirs)

        val first = service.run(codexProjectRequest()) as SetupService.SetupOutcome.Installed
        val before = Files.readString(first.path)
        val second = service.run(codexProjectRequest()) as SetupService.SetupOutcome.Installed

        second.changed shouldBe false
        Files.readString(second.path) shouldBe before
    }

    @Test
    fun `codex check and remove round-trip without touching other servers`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = codexService(dirs)
        val path = dirs.project.resolve(".codex/config.toml")
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            "model = \"o4-mini\"\n\n[mcp_servers.other]\ncommand = \"other\"\nargs = [\"x\"]\n",
        )

        (service.run(codexProjectRequest(check = true)) as SetupService.SetupOutcome.Checked).installed shouldBe false

        val installed = service.run(codexProjectRequest()) as SetupService.SetupOutcome.Installed
        installed.changed shouldBe true
        val stored = Files.readString(installed.path)
        stored shouldContain "model = \"o4-mini\""
        stored shouldContain "[mcp_servers.other]"
        stored shouldContain SetupService.desiredCodexBlock().trim()
        SetupService.isInstalledAt(path, SetupService.Agent.CODEX) shouldBe true
        (service.run(codexProjectRequest(check = true)) as SetupService.SetupOutcome.Checked).installed shouldBe true

        val removed = service.run(codexProjectRequest(remove = true)) as SetupService.SetupOutcome.Removed
        removed.changed shouldBe true
        Files.readString(removed.path) shouldBe
            "model = \"o4-mini\"\n\n[mcp_servers.other]\ncommand = \"other\"\nargs = [\"x\"]\n"
        SetupService.isInstalledAt(path, SetupService.Agent.CODEX) shouldBe false

        val again = service.run(codexProjectRequest(remove = true)) as SetupService.SetupOutcome.Removed
        again.changed shouldBe false
    }

    @Test
    fun `codex install repairs a stale entry pointing elsewhere`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = codexService(dirs)
        val path = dirs.project.resolve(".codex/config.toml")
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            "[mcp_servers.jdx]\ncommand = \"other-server\"\nargs = [\"x\"]\n",
        )

        SetupService.isInstalledAt(path, SetupService.Agent.CODEX) shouldBe false
        val outcome = service.run(codexProjectRequest()) as SetupService.SetupOutcome.Installed

        outcome.changed shouldBe true
        Files.readString(outcome.path) shouldBe SetupService.desiredCodexBlock()
    }

    @Test
    fun `codex install preserves an absolute jdx command path as installed`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = codexService(dirs)
        val path = dirs.project.resolve(".codex/config.toml")
        Files.createDirectories(path.parent)
        val text = "[mcp_servers.jdx]\ncommand = \"/home/u/.local/bin/jdx\"\nargs = [\"mcp\"]\n"
        Files.writeString(path, text)

        SetupService.isInstalledAt(path, SetupService.Agent.CODEX) shouldBe true
        val outcome = service.run(codexProjectRequest()) as SetupService.SetupOutcome.Installed

        outcome.changed shouldBe false
        Files.readString(outcome.path) shouldBe text
    }

    @Test
    fun `codex probe reads quoted headers comments and multiline arrays`(@TempDir root: Path) {
        // Quoted table segments and trailing comments.
        SetupService.isCodexInstalledText(
            "[ mcp_servers.\"jdx\" ] # owned\ncommand = \"jdx\" # launcher\nargs = [\"mcp\"]\n",
        ) shouldBe true
        // Literal strings and a multi-line args array.
        SetupService.isCodexInstalledText(
            "[mcp_servers.jdx]\ncommand = 'jdx'\nargs = [\n  \"mcp\",\n]\n",
        ) shouldBe true
        // Root-level dotted keys.
        SetupService.isCodexInstalledText(
            "mcp_servers.jdx.command = \"jdx\"\nmcp_servers.jdx.args = [\"mcp\"]\n",
        ) shouldBe true
        // A hostile lookalike: brackets inside strings never confuse the scan.
        SetupService.isCodexInstalledText(
            "model = \"[mcp_servers.jdx]\"\n[mcp_servers.other]\ncommand = \"other\"\n",
        ) shouldBe false
        // A `[[array]]` header closes the owned table: keys below it are not ours.
        SetupService.isCodexInstalledText(
            "[mcp_servers.jdx]\ncommand = \"jdx\"\nargs = [\"mcp\"]\n[[plugins]]\ncommand = \"evil\"\n",
        ) shouldBe true
        SetupService.isCodexInstalledText(
            "[mcp_servers.other]\ncommand = \"jdx\"\nargs = [\"mcp\"]\n",
        ) shouldBe false
        SetupService.isCodexInstalledText("") shouldBe false
        SetupService.isCodexInstalledText(null) shouldBe false
        SetupService.hasCodexEntryText("[mcp_servers.jdx]\ncommand = \"other\"\n") shouldBe true
        SetupService.hasCodexEntryText("[mcp_servers.other]\ncommand = \"other\"\n") shouldBe false
    }

    @Test
    fun `targetPath resolves the codex scope paths`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project, dirs.home.resolve(".codex"))

        service.targetPath(SetupService.Agent.CODEX, SetupService.Scope.PROJECT) shouldBe
            dirs.project.resolve(".codex/config.toml")
        service.targetPath(SetupService.Agent.CODEX, SetupService.Scope.SYSTEM) shouldBe
            dirs.home.resolve(".codex/config.toml")
    }

    @Test
    fun `codex version probing names presence without guessing`(@TempDir root: Path) {
        SetupService.probeCodexVersion(
            emptyList(),
            ProcessRunner { _, _ -> ProcessOutcome(0, "x", "") },
        ).version shouldBe SetupService.CodexVersion.ABSENT
    }

    @Test
    fun `codex version probe classifies answers`(@TempDir root: Path) {
        val bin = root.resolve("bin").also { Files.createDirectories(it) }
        Files.createFile(bin.resolve("codex")).toFile().setExecutable(true)

        val present = SetupService.probeCodexVersion(
            listOf(bin),
            ProcessRunner { _, _ -> ProcessOutcome(0, "codex-cli 0.157.1", "") },
        )
        present.version shouldBe SetupService.CodexVersion.PRESENT
        present.raw shouldBe "codex-cli 0.157.1"
        present.binary shouldBe "codex"

        val blank = SetupService.probeCodexVersion(
            listOf(bin),
            ProcessRunner { _, _ -> ProcessOutcome(0, "  ", "") },
        )
        blank.version shouldBe SetupService.CodexVersion.UNKNOWN

        val failing = SetupService.probeCodexVersion(
            listOf(bin),
            throwingRunner("boom"),
        )
        failing.version shouldBe SetupService.CodexVersion.UNKNOWN
    }

    @Test
    fun `codex probe resolves the exe shim on windows`(@TempDir root: Path) {
        val bin = root.resolve("bin").also { Files.createDirectories(it) }
        Files.createFile(bin.resolve("codex.exe")).toFile().setExecutable(true)
        val runner = ProcessRunner { _, _ -> ProcessOutcome(0, "codex-cli 0.157.1", "") }

        val info = SetupService.probeCodexVersion(listOf(bin), runner, osName = "Windows 11")

        info.version shouldBe SetupService.CodexVersion.PRESENT
        info.raw shouldBe "codex-cli 0.157.1"
        info.binary shouldBe "codex"
    }

    @Test
    fun `describeCodexVersion covers every variant`() {
        SetupService.describeCodexVersion(
            SetupService.CodexVersionInfo(SetupService.CodexVersion.PRESENT, "codex-cli 0.157.1", "codex"),
        ) shouldBe "codex (codex-cli 0.157.1)"
        SetupService.describeCodexVersion(
            SetupService.CodexVersionInfo(SetupService.CodexVersion.ABSENT),
        ) shouldBe "codex not found on PATH"
        SetupService.describeCodexVersion(
            SetupService.CodexVersionInfo(SetupService.CodexVersion.UNKNOWN, "banana", "codex"),
        ) shouldBe "codex version unknown (banana)"
        SetupService.describeCodexVersion(
            SetupService.CodexVersionInfo(SetupService.CodexVersion.UNKNOWN),
        ) shouldBe "codex version unknown"
    }

    // -- GitHub Copilot CLI backend (.mcp.json / ~/.copilot/mcp-config.json, mcpServers.jdx) --

    private fun copilotProjectRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.COPILOT,
        scope = SetupService.Scope.PROJECT,
        check = check,
        remove = remove,
    )

    private fun copilotSystemRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.COPILOT,
        scope = SetupService.Scope.SYSTEM,
        check = check,
        remove = remove,
    )

    /** A service whose Copilot system dir is pinned under the fake home (never the ambient `$COPILOT_HOME`). */
    private fun copilotService(dirs: FakeDirs): SetupService =
        SetupService(dirs.home, dirs.project, null, dirs.home.resolve(".copilot"))

    @Test
    fun `parseAgent covers copilot spellings`() {
        SetupService.parseAgent("copilot") shouldBe SetupService.Agent.COPILOT
        SetupService.parseAgent("Copilot") shouldBe SetupService.Agent.COPILOT
        SetupService.parseAgent("github-copilot") shouldBe SetupService.Agent.COPILOT
        SetupService.parseAgent("GitHub Copilot") shouldBe SetupService.Agent.COPILOT
        SetupService.parseAgent("copilot-cli") shouldBe SetupService.Agent.COPILOT
        SetupService.parseAgent("COPILOT_CLI") shouldBe SetupService.Agent.COPILOT
    }

    @Test
    fun `a fresh copilot project install creates dot mcp json with the jdx entry`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = copilotService(dirs)

        val outcome = service.run(copilotProjectRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.changed shouldBe true
        installed.path shouldBe dirs.project.resolve(".mcp.json")
        setupExitCode(outcome) shouldBe 0
        val stored = Json.parseToJsonElement(Files.readString(installed.path)).jsonObject
        val entry = stored["mcpServers"]?.jsonObject?.get("jdx")?.jsonObject
        entry?.get("type")?.jsonPrimitive?.content shouldBe "local"
        entry?.get("command")?.jsonPrimitive?.content shouldBe "jdx"
        entry?.get("args").toString() shouldBe """["mcp"]"""
        SetupService.isInstalledRoot(stored, SetupService.Agent.COPILOT) shouldBe true
        SetupService.isInstalledAt(installed.path, SetupService.Agent.COPILOT) shouldBe true
        // Cross-wiring is inherent, not a bug: Claude Code reads the same
        // `.mcp.json` / `mcpServers.jdx` shape, so one install wires both
        // readers (unlike Kilo vs OpenCode, which use different files).
        SetupService.isInstalledAt(installed.path, SetupService.Agent.CLAUDE_CODE) shouldBe true
    }

    @Test
    fun `copilot install preserves other servers and never clobbers`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve(".mcp.json"),
            """{"mcpServers":{"other":{"type":"local","command":"other","args":["x"]}}}""",
        )
        val service = copilotService(dirs)

        val outcome = service.run(copilotProjectRequest()) as SetupService.SetupOutcome.Installed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(outcome.path)).jsonObject
        stored["mcpServers"]?.jsonObject?.get("other")?.jsonObject
            ?.get("command")?.jsonPrimitive?.content shouldBe "other"
        SetupService.isInstalledRoot(stored, SetupService.Agent.COPILOT) shouldBe true
    }

    @Test
    fun `a second copilot install is a byte-identical no-op`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = copilotService(dirs)

        val first = service.run(copilotProjectRequest()) as SetupService.SetupOutcome.Installed
        val before = Files.readAllBytes(first.path)
        val second = service.run(copilotProjectRequest())

        (second as SetupService.SetupOutcome.Installed).changed shouldBe false
        Files.readAllBytes(first.path) shouldBe before
    }

    @Test
    fun `copilot check reports without writing`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = copilotService(dirs)

        val absent = service.run(copilotProjectRequest(check = true))
        absent as SetupService.SetupOutcome.Checked
        absent.installed shouldBe false
        setupExitCode(absent) shouldBe 1
        Files.exists(dirs.project.resolve(".mcp.json")) shouldBe false

        service.run(copilotProjectRequest())
        val present = service.run(copilotProjectRequest(check = true))
        present as SetupService.SetupOutcome.Checked
        present.installed shouldBe true
        setupExitCode(present) shouldBe 0
    }

    @Test
    fun `copilot remove deletes only the jdx entry and drops the emptied namespace`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = copilotService(dirs)
        service.run(copilotProjectRequest())

        val outcome = service.run(copilotProjectRequest(remove = true)) as SetupService.SetupOutcome.Removed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(dirs.project.resolve(".mcp.json"))).jsonObject
        stored.containsKey("mcpServers") shouldBe false
        SetupService.isInstalledAt(dirs.project.resolve(".mcp.json"), SetupService.Agent.COPILOT) shouldBe false

        val again = service.run(copilotProjectRequest(remove = true))
        (again as SetupService.SetupOutcome.Removed).changed shouldBe false
        setupExitCode(again) shouldBe 0
    }

    @Test
    fun `copilot remove keeps other servers`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve(".mcp.json"),
            """{"mcpServers":{"other":{"type":"local","command":"other","args":["x"]}}}""",
        )
        val service = copilotService(dirs)
        service.run(copilotProjectRequest())

        val outcome = service.run(copilotProjectRequest(remove = true)) as SetupService.SetupOutcome.Removed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(dirs.project.resolve(".mcp.json"))).jsonObject
        stored["mcpServers"]?.jsonObject?.containsKey("jdx") shouldBe false
        stored["mcpServers"]?.jsonObject?.containsKey("other") shouldBe true
    }

    @Test
    fun `copilot system scope honours the injected copilot home`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project, null, root.resolve("custom-copilot-home"))

        val outcome = service.run(copilotSystemRequest()) as SetupService.SetupOutcome.Installed

        outcome.path shouldBe root.resolve("custom-copilot-home/mcp-config.json")
        SetupService.isInstalledAt(outcome.path, SetupService.Agent.COPILOT) shouldBe true
    }

    @Test
    fun `copilot system scope defaults under the user home`(@TempDir root: Path) {
        val dirs = fakeDirs(root)

        SetupService.copilotSystemConfigPath(dirs.home, dirs.home.resolve(".copilot")) shouldBe
            dirs.home.resolve(".copilot/mcp-config.json")
        SetupService.copilotSystemConfigPath(dirs.home) shouldBe
            dirs.home.resolve(".copilot/mcp-config.json")
    }

    @Test
    fun `copilot project scope walks up to the nearest mcp json`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(dirs.project.resolve(".mcp.json"), "{}")
        val nested = dirs.project.resolve("a/b").also { Files.createDirectories(it) }
        val service = SetupService(dirs.home, nested, null, dirs.home.resolve(".copilot"))

        val outcome = service.run(copilotProjectRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.path shouldBe dirs.project.resolve(".mcp.json")
        Files.exists(nested.resolve(".mcp.json")) shouldBe false
    }

    @Test
    fun `copilot project scope falls back to the shared github mcp json`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.createDirectories(dirs.project.resolve(".github"))
        Files.writeString(dirs.project.resolve(".github/mcp.json"), "{}")
        val nested = dirs.project.resolve("a/b").also { Files.createDirectories(it) }
        val service = SetupService(dirs.home, nested, null, dirs.home.resolve(".copilot"))

        val outcome = service.run(copilotProjectRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.path shouldBe dirs.project.resolve(".github/mcp.json")
        SetupService.isInstalledAt(installed.path, SetupService.Agent.COPILOT) shouldBe true
    }

    @Test
    fun `copilot project scope prefers the local mcp json over the shared one`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(dirs.project.resolve(".mcp.json"), "{}")
        Files.createDirectories(dirs.project.resolve(".github"))
        Files.writeString(dirs.project.resolve(".github/mcp.json"), "{}")
        val service = copilotService(dirs)

        val outcome = service.run(copilotProjectRequest()) as SetupService.SetupOutcome.Installed

        outcome.path shouldBe dirs.project.resolve(".mcp.json")
    }

    @Test
    fun `a copilot corrupt config exits 5 and names the file`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(dirs.project.resolve(".mcp.json"), "{ not json,")
        val service = copilotService(dirs)

        val outcome = service.run(copilotProjectRequest())

        outcome as SetupService.SetupOutcome.Corrupt
        setupExitCode(outcome) shouldBe 5
        outcome.path shouldBe dirs.project.resolve(".mcp.json")
    }

    @Test
    fun `copilot entry probe accepts stdio type absolute paths and windows shims`(@TempDir root: Path) {
        val stdio = buildJsonObject {
            put("type", "stdio")
            put("command", "jdx")
            put("args", JsonArray(listOf(JsonPrimitive("mcp"))))
        }
        SetupService.isCopilotJdxEntry(stdio) shouldBe true
        val absolute = buildJsonObject {
            put("type", "local")
            put("command", "/home/dev/.local/bin/jdx")
            put("args", JsonArray(listOf(JsonPrimitive("mcp"))))
        }
        SetupService.isCopilotJdxEntry(absolute) shouldBe true
        val shim = buildJsonObject {
            put("type", "local")
            put("command", "C:\\tools\\jdx.exe")
            put("args", JsonArray(listOf(JsonPrimitive("mcp"))))
        }
        SetupService.isCopilotJdxEntry(shim) shouldBe true
        val withTools = buildJsonObject {
            put("type", "local")
            put("command", "jdx")
            put("args", JsonArray(listOf(JsonPrimitive("mcp"))))
            put("tools", JsonArray(listOf(JsonPrimitive("*"))))
        }
        SetupService.isCopilotJdxEntry(withTools) shouldBe true
        val missingType = buildJsonObject {
            put("command", "jdx")
            put("args", JsonArray(listOf(JsonPrimitive("mcp"))))
        }
        SetupService.isCopilotJdxEntry(missingType) shouldBe true
    }

    @Test
    fun `copilot entry probe rejects foreign commands and remote types`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve(".mcp.json"),
            """{"mcpServers":{"jdx":{"type":"local","command":"other","args":["mcp"]}}}""",
        )
        SetupService.isInstalledAt(
            dirs.project.resolve(".mcp.json"),
            SetupService.Agent.COPILOT,
        ) shouldBe false
        val http = buildJsonObject {
            put("type", "http")
            put("url", "https://mcp.example.com/mcp")
        }
        SetupService.isCopilotJdxEntry(http) shouldBe false
    }

    @Test
    fun `copilot probe sees the bare top-level format in project files`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve(".mcp.json"),
            """{"jdx":{"type":"local","command":"jdx","args":["mcp"]}}""",
        )
        val service = copilotService(dirs)

        SetupService.isInstalledAt(
            dirs.project.resolve(".mcp.json"),
            SetupService.Agent.COPILOT,
        ) shouldBe true
        // A bare-format file is completed via the `mcpServers` wrapper (the
        // only shape the user config accepts); the bare entry is preserved.
        val completed = service.run(copilotProjectRequest()) as SetupService.SetupOutcome.Installed
        completed.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(completed.path)).jsonObject
        stored["jdx"]?.jsonObject?.get("command")?.jsonPrimitive?.content shouldBe "jdx"
        SetupService.isInstalledAt(completed.path, SetupService.Agent.COPILOT) shouldBe true

        // Remove drops both the wrapper and the owned bare entry.
        val removed = service.run(copilotProjectRequest(remove = true)) as SetupService.SetupOutcome.Removed
        removed.changed shouldBe true
        val pruned = Json.parseToJsonElement(Files.readString(removed.path)).jsonObject
        pruned.containsKey("jdx") shouldBe false
        pruned.containsKey("mcpServers") shouldBe false
        SetupService.isInstalledAt(removed.path, SetupService.Agent.COPILOT) shouldBe false
    }

    @Test
    fun `copilot remove leaves a foreign bare jdx entry untouched`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.writeString(
            dirs.project.resolve(".mcp.json"),
            """{"jdx":{"type":"local","command":"other","args":["x"]},"other":{"a":1}}""",
        )
        val service = copilotService(dirs)
        service.run(copilotProjectRequest())

        val removed = service.run(copilotProjectRequest(remove = true)) as SetupService.SetupOutcome.Removed

        removed.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(removed.path)).jsonObject
        stored["jdx"]?.jsonObject?.get("command")?.jsonPrimitive?.content shouldBe "other"
        stored["other"]?.jsonObject?.get("a")?.jsonPrimitive?.content shouldBe "1"
    }

    @Test
    fun `targetPath resolves the copilot scope paths`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = copilotService(dirs)

        service.targetPath(SetupService.Agent.COPILOT, SetupService.Scope.PROJECT) shouldBe
            dirs.project.resolve(".mcp.json")
        service.targetPath(SetupService.Agent.COPILOT, SetupService.Scope.SYSTEM) shouldBe
            dirs.home.resolve(".copilot/mcp-config.json")
    }

    @Test
    fun `copilot version probing names presence without guessing`(@TempDir root: Path) {
        SetupService.probeCopilotVersion(
            emptyList(),
            ProcessRunner { _, _ -> ProcessOutcome(0, "x", "") },
        ).version shouldBe SetupService.CopilotVersion.ABSENT
    }

    @Test
    fun `copilot version probe classifies answers`(@TempDir root: Path) {
        val bin = root.resolve("bin").also { Files.createDirectories(it) }
        Files.createFile(bin.resolve("copilot")).toFile().setExecutable(true)

        val present = SetupService.probeCopilotVersion(
            listOf(bin),
            ProcessRunner { _, _ -> ProcessOutcome(0, "GitHub Copilot CLI 1.0.88.", "") },
        )
        present.version shouldBe SetupService.CopilotVersion.PRESENT
        present.raw shouldBe "GitHub Copilot CLI 1.0.88."
        present.binary shouldBe "copilot"

        val blank = SetupService.probeCopilotVersion(
            listOf(bin),
            ProcessRunner { _, _ -> ProcessOutcome(0, "  ", "") },
        )
        blank.version shouldBe SetupService.CopilotVersion.UNKNOWN

        val failing = SetupService.probeCopilotVersion(
            listOf(bin),
            throwingRunner("boom"),
        )
        failing.version shouldBe SetupService.CopilotVersion.UNKNOWN
    }

    @Test
    fun `copilot probe resolves the exe shim on windows`(@TempDir root: Path) {
        val bin = root.resolve("bin").also { Files.createDirectories(it) }
        Files.createFile(bin.resolve("copilot.exe")).toFile().setExecutable(true)
        val runner = ProcessRunner { _, _ -> ProcessOutcome(0, "GitHub Copilot CLI 1.0.88.", "") }

        val info = SetupService.probeCopilotVersion(listOf(bin), runner, osName = "Windows 11")

        info.version shouldBe SetupService.CopilotVersion.PRESENT
        info.raw shouldBe "GitHub Copilot CLI 1.0.88."
        info.binary shouldBe "copilot"
    }

    @Test
    fun `describeCopilotVersion covers every variant`() {
        SetupService.describeCopilotVersion(
            SetupService.CopilotVersionInfo(SetupService.CopilotVersion.PRESENT, "GitHub Copilot CLI 1.0.88.", "copilot"),
        ) shouldBe "copilot (GitHub Copilot CLI 1.0.88.)"
        SetupService.describeCopilotVersion(
            SetupService.CopilotVersionInfo(SetupService.CopilotVersion.ABSENT),
        ) shouldBe "copilot not found on PATH"
        SetupService.describeCopilotVersion(
            SetupService.CopilotVersionInfo(SetupService.CopilotVersion.UNKNOWN, "banana", "copilot"),
        ) shouldBe "copilot version unknown (banana)"
        SetupService.describeCopilotVersion(
            SetupService.CopilotVersionInfo(SetupService.CopilotVersion.UNKNOWN),
        ) shouldBe "copilot version unknown"
    }

    // -- generative family: the JSON merge never loses foreign keys --

    @Test
    fun `copilot install is a fixed point over generated configs`() = runBlocking<Unit> {
        checkAll(200, Arb.string(0..40)) { noise ->
            val clean = noise.replace(Regex("[\\p{Cntrl}\"\\\\]"), " ").trim()
            val foreign = buildJsonObject {
                put("unrelated-$clean-x", "kept")
            }
            val once = SetupService.mergeCopilotInstall(foreign)
            SetupService.isCopilotInstalled(once) shouldBe true
            SetupService.mergeCopilotInstall(once) shouldBe once
            once["unrelated-$clean-x"]?.jsonPrimitive?.content shouldBe "kept"
        }
    }

    @Test
    fun `copilot remove restores generated foreign content`() = runBlocking<Unit> {
        checkAll(200, Arb.string(0..40)) { noise ->
            val clean = noise.replace(Regex("[\\p{Cntrl}\"\\\\]"), " ").trim()
            val foreign = buildJsonObject {
                put("unrelated-$clean-x", "kept")
            }
            val installed = SetupService.mergeCopilotInstall(foreign)
            SetupService.mergeCopilotRemove(installed) shouldBe foreign
        }
    }

    // -- generative family: the TOML merge never loses foreign lines --

    @Test
    fun `codex install is a fixed point over generated configs`() = runBlocking<Unit> {
        checkAll(200, Arb.string(0..120)) { noise ->
            val clean = noise.replace(Regex("[\\p{Cntrl}]"), " ")
                .replace("\"", "'").replace("[", "(").replace("]", ")").replace("=", "-")
            val text = "# $clean\n[other]\nkey = \"v\"\n"
            val once = SetupService.mergeCodexInstall(text)
            SetupService.isCodexInstalledText(once) shouldBe true
            SetupService.mergeCodexInstall(once) shouldBe once
            once shouldContain "# $clean"
            once shouldContain "[other]"
        }
    }

    @Test
    fun `codex remove restores generated foreign content byte-free`() = runBlocking<Unit> {
        checkAll(200, Arb.string(0..120)) { noise ->
            val clean = noise.replace(Regex("[\\p{Cntrl}]"), " ")
                .replace("\"", "'").replace("[", "(").replace("]", ")").replace("=", "-")
            val foreign = "# $clean\n[other]\nkey = \"v\"\n"
            val installed = SetupService.mergeCodexInstall(foreign)
            SetupService.mergeCodexRemove(installed) shouldBe foreign
        }
    }

    // -- Antigravity backend (.agents/mcp_config.json / ~/.gemini/config/mcp_config.json, mcpServers.jdx) --
    // Verified against a real install: `agy` 1.3.0 (`agy mcp add jdx -- jdx
    // mcp` writes `~/.gemini/config/mcp_config.json`; `agy mcp list`
    // recognises the entry; workspace overrides live in
    // `.agents/mcp_config.json` per https://antigravity.google/docs/mcp).

    private fun antigravityProjectRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.ANTIGRAVITY,
        scope = SetupService.Scope.PROJECT,
        check = check,
        remove = remove,
    )

    private fun antigravitySystemRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.ANTIGRAVITY,
        scope = SetupService.Scope.SYSTEM,
        check = check,
        remove = remove,
    )

    @Test
    fun `parseAgent covers antigravity spellings`() {
        SetupService.parseAgent("antigravity") shouldBe SetupService.Agent.ANTIGRAVITY
        SetupService.parseAgent("Antigravity") shouldBe SetupService.Agent.ANTIGRAVITY
        SetupService.parseAgent("agy") shouldBe SetupService.Agent.ANTIGRAVITY
        SetupService.parseAgent("AGY") shouldBe SetupService.Agent.ANTIGRAVITY
        SetupService.parseAgent("antigravity-cli") shouldBe SetupService.Agent.ANTIGRAVITY
        SetupService.parseAgent("ANTIGRAVITY_CLI") shouldBe SetupService.Agent.ANTIGRAVITY
    }

    @Test
    fun `a fresh antigravity project install creates dot-agents mcp config with the jdx entry`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(antigravityProjectRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.changed shouldBe true
        installed.path shouldBe dirs.project.resolve(".agents/mcp_config.json")
        setupExitCode(outcome) shouldBe 0
        val stored = Json.parseToJsonElement(Files.readString(installed.path)).jsonObject
        val entry = stored["mcpServers"]?.jsonObject?.get("jdx")?.jsonObject
        entry?.get("command")?.jsonPrimitive?.content shouldBe "jdx"
        entry?.get("args").toString() shouldBe """["mcp"]"""
        SetupService.isInstalledRoot(stored, SetupService.Agent.ANTIGRAVITY) shouldBe true
        SetupService.isInstalledAt(installed.path, SetupService.Agent.ANTIGRAVITY) shouldBe true
        // The Claude/Cursor probe shares the shape but each agent checks its
        // own file; an Antigravity file is wired for Antigravity here.
        SetupService.isInstalledRoot(stored, SetupService.Agent.CURSOR) shouldBe true
    }

    @Test
    fun `antigravity install preserves other servers and never clobbers`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.createDirectories(dirs.project.resolve(".agents"))
        Files.writeString(
            dirs.project.resolve(".agents/mcp_config.json"),
            """{"mcpServers":{"other":{"command":"other","args":["x"]}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(antigravityProjectRequest()) as SetupService.SetupOutcome.Installed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(outcome.path)).jsonObject
        stored["mcpServers"]?.jsonObject?.get("other")?.jsonObject
            ?.get("command")?.jsonPrimitive?.content shouldBe "other"
        SetupService.isInstalledRoot(stored, SetupService.Agent.ANTIGRAVITY) shouldBe true
    }

    @Test
    fun `a second antigravity install is a byte-identical no-op`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val first = service.run(antigravityProjectRequest()) as SetupService.SetupOutcome.Installed
        val before = Files.readAllBytes(first.path)
        val second = service.run(antigravityProjectRequest())

        (second as SetupService.SetupOutcome.Installed).changed shouldBe false
        Files.readAllBytes(first.path) shouldBe before
    }

    @Test
    fun `antigravity check reports without writing`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val absent = service.run(antigravityProjectRequest(check = true))
        absent as SetupService.SetupOutcome.Checked
        absent.installed shouldBe false
        setupExitCode(absent) shouldBe 1
        Files.exists(dirs.project.resolve(".agents/mcp_config.json")) shouldBe false

        service.run(antigravityProjectRequest())
        val present = service.run(antigravityProjectRequest(check = true))
        present as SetupService.SetupOutcome.Checked
        present.installed shouldBe true
        setupExitCode(present) shouldBe 0
    }

    @Test
    fun `antigravity remove deletes only the jdx entry and drops the emptied namespace`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)
        service.run(antigravityProjectRequest())

        val outcome = service.run(antigravityProjectRequest(remove = true)) as SetupService.SetupOutcome.Removed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(dirs.project.resolve(".agents/mcp_config.json"))).jsonObject
        stored.containsKey("mcpServers") shouldBe false
        SetupService.isInstalledAt(dirs.project.resolve(".agents/mcp_config.json"), SetupService.Agent.ANTIGRAVITY) shouldBe false

        val again = service.run(antigravityProjectRequest(remove = true))
        (again as SetupService.SetupOutcome.Removed).changed shouldBe false
        setupExitCode(again) shouldBe 0
    }

    @Test
    fun `antigravity remove keeps other servers`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.createDirectories(dirs.project.resolve(".agents"))
        Files.writeString(
            dirs.project.resolve(".agents/mcp_config.json"),
            """{"mcpServers":{"other":{"command":"other","args":["x"]}}}""",
        )
        val service = SetupService(dirs.home, dirs.project)
        service.run(antigravityProjectRequest())

        val outcome = service.run(antigravityProjectRequest(remove = true)) as SetupService.SetupOutcome.Removed

        outcome.changed shouldBe true
        val stored = Json.parseToJsonElement(Files.readString(dirs.project.resolve(".agents/mcp_config.json"))).jsonObject
        stored["mcpServers"]?.jsonObject?.containsKey("jdx") shouldBe false
        stored["mcpServers"]?.jsonObject?.containsKey("other") shouldBe true
    }

    @Test
    fun `antigravity system scope targets the gemini config under the fake home`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        val created = service.run(antigravitySystemRequest()) as SetupService.SetupOutcome.Installed

        created.path shouldBe dirs.home.resolve(".gemini/config/mcp_config.json")
        SetupService.isInstalledAt(created.path, SetupService.Agent.ANTIGRAVITY) shouldBe true
    }

    @Test
    fun `antigravity project scope walks up to the nearest dot-agents mcp config`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.createDirectories(dirs.project.resolve(".agents"))
        Files.writeString(dirs.project.resolve(".agents/mcp_config.json"), "{}")
        val nested = dirs.project.resolve("a/b").also { Files.createDirectories(it) }
        val service = SetupService(dirs.home, nested)

        val outcome = service.run(antigravityProjectRequest())

        val installed = outcome as SetupService.SetupOutcome.Installed
        installed.path shouldBe dirs.project.resolve(".agents/mcp_config.json")
        Files.exists(nested.resolve(".agents/mcp_config.json")) shouldBe false
    }

    @Test
    fun `an antigravity corrupt config exits 5 and names the file`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.createDirectories(dirs.project.resolve(".agents"))
        Files.writeString(dirs.project.resolve(".agents/mcp_config.json"), "{ not json,")
        val service = SetupService(dirs.home, dirs.project)

        val outcome = service.run(antigravityProjectRequest())

        outcome as SetupService.SetupOutcome.Corrupt
        setupExitCode(outcome) shouldBe 5
    }

    @Test
    fun `antigravity install over real agy bytes is a byte-identical no-op`(@TempDir root: Path) {
        // Byte-identical to what `agy mcp add jdx -- jdx mcp` wrote under an
        // isolated HOME (`agy` 1.3.0): key order and the `disabled` flag are
        // the real CLI's, and our probe/install must accept them as-is.
        val dirs = fakeDirs(root)
        Files.createDirectories(dirs.project.resolve(".agents"))
        val realBytes = "{\n  \"mcpServers\": {\n    \"jdx\": {\n      \"args\": [\n        \"mcp\"\n      ],\n      \"command\": \"jdx\",\n      \"disabled\": false\n    }\n  }\n}\n"
        Files.writeString(dirs.project.resolve(".agents/mcp_config.json"), realBytes)
        val service = SetupService(dirs.home, dirs.project)

        SetupService.isInstalledAt(
            dirs.project.resolve(".agents/mcp_config.json"),
            SetupService.Agent.ANTIGRAVITY,
        ) shouldBe true
        val outcome = service.run(antigravityProjectRequest())

        (outcome as SetupService.SetupOutcome.Installed).changed shouldBe false
        Files.readString(dirs.project.resolve(".agents/mcp_config.json")) shouldBe realBytes
    }

    @Test
    fun `antigravity entry probe accepts disabled flags absolute paths and windows shims`(@TempDir root: Path) {
        val disabled = buildJsonObject {
            put("command", "jdx")
            put("args", JsonArray(listOf(JsonPrimitive("mcp"))))
            put("disabled", false)
        }
        SetupService.isAntigravityJdxEntry(disabled) shouldBe true
        val absolute = buildJsonObject {
            put("command", "/home/dev/.local/bin/jdx")
            put("args", JsonArray(listOf(JsonPrimitive("mcp"))))
        }
        SetupService.isAntigravityJdxEntry(absolute) shouldBe true
        val shim = buildJsonObject {
            put("command", "C:\\tools\\jdx.exe")
            put("args", JsonArray(listOf(JsonPrimitive("mcp"))))
        }
        SetupService.isAntigravityJdxEntry(shim) shouldBe true
    }

    @Test
    fun `antigravity entry probe rejects foreign commands`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        Files.createDirectories(dirs.project.resolve(".agents"))
        Files.writeString(
            dirs.project.resolve(".agents/mcp_config.json"),
            """{"mcpServers":{"jdx":{"command":"other","args":["mcp"]}}}""",
        )
        SetupService.isInstalledAt(
            dirs.project.resolve(".agents/mcp_config.json"),
            SetupService.Agent.ANTIGRAVITY,
        ) shouldBe false
    }

    @Test
    fun `targetPath resolves the antigravity scope paths`(@TempDir root: Path) {
        val dirs = fakeDirs(root)
        val service = SetupService(dirs.home, dirs.project)

        service.targetPath(SetupService.Agent.ANTIGRAVITY, SetupService.Scope.PROJECT) shouldBe
            dirs.project.resolve(".agents/mcp_config.json")
        service.targetPath(SetupService.Agent.ANTIGRAVITY, SetupService.Scope.SYSTEM) shouldBe
            dirs.home.resolve(".gemini/config/mcp_config.json")
    }

    @Test
    fun `antigravity version probing names presence without guessing`(@TempDir root: Path) {
        SetupService.probeAntigravityVersion(
            emptyList(),
            ProcessRunner { _, _ -> ProcessOutcome(0, "x", "") },
        ).version shouldBe SetupService.AntigravityVersion.ABSENT
    }

    @Test
    fun `antigravity version probe classifies answers`(@TempDir root: Path) {
        val bin = root.resolve("bin").also { Files.createDirectories(it) }
        Files.createFile(bin.resolve("agy")).toFile().setExecutable(true)

        val present = SetupService.probeAntigravityVersion(
            listOf(bin),
            ProcessRunner { _, _ -> ProcessOutcome(0, "1.3.0", "") },
        )
        present.version shouldBe SetupService.AntigravityVersion.PRESENT
        present.raw shouldBe "1.3.0"
        present.binary shouldBe "agy"

        val blank = SetupService.probeAntigravityVersion(
            listOf(bin),
            ProcessRunner { _, _ -> ProcessOutcome(0, "  ", "") },
        )
        blank.version shouldBe SetupService.AntigravityVersion.UNKNOWN

        val failing = SetupService.probeAntigravityVersion(
            listOf(bin),
            throwingRunner("boom"),
        )
        failing.version shouldBe SetupService.AntigravityVersion.UNKNOWN
    }

    @Test
    fun `antigravity probe resolves the exe shim on windows`(@TempDir root: Path) {
        val bin = root.resolve("bin").also { Files.createDirectories(it) }
        Files.createFile(bin.resolve("agy.exe")).toFile().setExecutable(true)
        val runner = ProcessRunner { _, _ -> ProcessOutcome(0, "1.3.0", "") }

        val info = SetupService.probeAntigravityVersion(listOf(bin), runner, osName = "Windows 11")

        info.version shouldBe SetupService.AntigravityVersion.PRESENT
        info.raw shouldBe "1.3.0"
        info.binary shouldBe "agy"
    }

    @Test
    fun `describeAntigravityVersion covers every variant`() {
        SetupService.describeAntigravityVersion(
            SetupService.AntigravityVersionInfo(SetupService.AntigravityVersion.PRESENT, "1.3.0", "agy"),
        ) shouldBe "antigravity (1.3.0)"
        SetupService.describeAntigravityVersion(
            SetupService.AntigravityVersionInfo(SetupService.AntigravityVersion.ABSENT),
        ) shouldBe "antigravity not found on PATH"
        SetupService.describeAntigravityVersion(
            SetupService.AntigravityVersionInfo(SetupService.AntigravityVersion.UNKNOWN, "banana", "agy"),
        ) shouldBe "antigravity version unknown (banana)"
        SetupService.describeAntigravityVersion(
            SetupService.AntigravityVersionInfo(SetupService.AntigravityVersion.UNKNOWN),
        ) shouldBe "antigravity version unknown"
    }

    @Test
    fun `antigravity install is a fixed point over generated configs`() = runBlocking<Unit> {
        checkAll(200, Arb.string(0..40)) { noise ->
            val clean = noise.replace(Regex("[\\p{Cntrl}\"\\\\]"), " ").trim()
            val foreign = buildJsonObject {
                put("unrelated-$clean-x", "kept")
            }
            val once = SetupService.mergeAntigravityInstall(foreign)
            SetupService.isAntigravityInstalled(once) shouldBe true
            SetupService.mergeAntigravityInstall(once) shouldBe once
            once["unrelated-$clean-x"]?.jsonPrimitive?.content shouldBe "kept"
        }
    }

    @Test
    fun `antigravity remove restores generated foreign content`() = runBlocking<Unit> {
        checkAll(200, Arb.string(0..40)) { noise ->
            val clean = noise.replace(Regex("[\\p{Cntrl}\"\\\\]"), " ").trim()
            val foreign = buildJsonObject {
                put("unrelated-$clean-x", "kept")
            }
            val installed = SetupService.mergeAntigravityInstall(foreign)
            SetupService.mergeAntigravityRemove(installed) shouldBe foreign
        }
    }
}
