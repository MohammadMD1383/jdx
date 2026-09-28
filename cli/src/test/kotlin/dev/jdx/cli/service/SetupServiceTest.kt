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
 * the OpenCode, Claude Code, Cursor, Kilo Code, and Codex CLI backends, merge
 * discipline, and the JSONC stripper. Every home and project dir is a fresh
 * temp dir — no test touches the real home.
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

    private fun codexSystemRequest(
        check: Boolean = false,
        remove: Boolean = false,
    ): SetupService.SetupRequest = SetupService.SetupRequest(
        agent = SetupService.Agent.CODEX,
        scope = SetupService.Scope.SYSTEM,
        check = check,
        remove = remove,
    )

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
}
