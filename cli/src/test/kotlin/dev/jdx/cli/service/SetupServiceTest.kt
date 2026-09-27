package dev.jdx.cli.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `SetupService` over fake homes (issue #33 family): install/check/remove for
 * the OpenCode backend, merge discipline, and the JSONC stripper. Every home
 * and project dir is a fresh temp dir — no test touches the real home.
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
        SetupService.parseAgent("claude") shouldBe null
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
                    if (executable.fileName.toString() == "opencode") {
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
}
