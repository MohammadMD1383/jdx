package dev.jdx.cli.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * `jdx setup --agent <name> --scope <project|system>` (issue #33 family).
 *
 * Writes, checks, and removes the MCP server entry that launches `jdx mcp` in
 * third-party agent configs. Three backends share this service behind the
 * [Agent] seam: OpenCode (`opencode.json[c]`, v1+v2 entries), Claude Code
 * (`.mcp.json` / `~/.claude.json`, `mcpServers.jdx`), and Kilo Code (a fork
 * of OpenCode — its `mcp` map carries the same v1-style local-server shape).
 * All filesystem behaviour lives here; the Clikt command only parses flags,
 * renders, and maps exit codes (D-004).
 *
 * OpenCode layout (verified against a real install: global
 * `~/.config/opencode/opencode.jsonc` with `"$schema":
 * "https://opencode.ai/config.json"`; project `opencode.json`/`opencode.jsonc`
 * in the checkout). OpenCode is beta-testing v2 alongside v1 (issue: both
 * lines must keep working until v1 is deprecated and removed), and the two
 * lines shape the MCP entry differently:
 *
 * - v1: servers live directly under the top-level `mcp` object keyed by
 *   server name, each a `{ "type": "local", "command": [...], "enabled": ... }`
 *   object (see https://opencode.ai/config.json `McpLocalConfig`). The entry
 *   this service owns is `mcp.jdx`.
 * - v2: servers live under `mcp.servers`, and `enabled` is replaced by the
 *   inverse `disabled` (see https://opencode.ai/v2/docs/mcp-servers and the
 *   v1→v2 migration guide). The entry this service owns is
 *   `mcp.servers.jdx`. V2 keeps reading the v1 shape, but the native shape
 *   takes precedence on conflict — so this service writes **both** entries
 *   and treats either as "wired" when probing.
 *
 * Kilo Code layout (verified against the published docs at
 * https://kilo.ai/docs/automate/mcp/using-in-kilo-code and
 * https://kilo.ai/docs/getting-started/settings: global
 * `~/.config/kilo/kilo.jsonc`, project `kilo.jsonc` in the project root or
 * `.kilo/kilo.jsonc` for a cleaner setup, the `.kilo/` variant taking priority
 * when both exist). MCP servers live under the top-level `mcp` key in the
 * same v1-style shape (`type: local`, `command`, `enabled`) — so the Kilo
 * backend owns the single entry `mcp.jdx` and never writes the OpenCode v2
 * `mcp.servers` namespace. Fresh Kilo files carry no `$schema` (that URL
 * names the OpenCode config schema).
 *
 * Merge discipline: the target file is parsed leniently (JSONC comments and
 * trailing commas are accepted), every unrelated key is preserved byte-free —
 * only the owned entries are added, replaced, or removed. A second
 * run is a byte-exact no-op: when the desired entries are already present
 * nothing is rewritten, so comments and formatting survive idempotent re-runs.
 */
class SetupService(
    private val userHome: Path,
    private val projectDir: Path,
) {
    /** Agents with setup support: OpenCode, Claude Code, and Kilo Code. */
    enum class Agent(val cliName: String) {
        OPENCODE("opencode"),
        CLAUDE_CODE("claude-code"),
        KILO("kilo"),
    }

    /** Where the entry is written: the checkout or the user's global config. */
    enum class Scope(val cliName: String) {
        PROJECT("project"),
        SYSTEM("system"),
    }

    /**
     * Which OpenCode major line is installed for use. V1 stable reports `1.x`;
     * the v2 beta reports `0.0.0-next-*`/`0.0.0-beta-*`/`0.0.0-dev-*` (a future
     * stable would report `2.x`). `ABSENT` means no `opencode` binary on PATH;
     * `UNKNOWN` means a binary answered but its version is unparseable — the
     * detail names the raw output so a human can judge instead of jdx guessing.
     */
    enum class OpencodeVersion(val cliName: String) {
        V1("v1"),
        V2("v2"),
        ABSENT("absent"),
        UNKNOWN("unknown"),
    }

    /**
     * Whether the `claude` binary is installed for use. Claude Code has no
     * v1/v2 line split to classify — `PRESENT` means the binary answered
     * `--version`; `ABSENT` means no `claude` on PATH; `UNKNOWN` means it is
     * present but never answered (or answered blank).
     */
    enum class ClaudeVersion(val cliName: String) {
        PRESENT("present"),
        ABSENT("absent"),
        UNKNOWN("unknown"),
    }

    /**
     * Best-effort answer to "which opencode will run this config". [raw] is the
     * trimmed `--version` output (null when the binary never answered);
     * [binary] names the probed executable (`opencode`, or the legacy `opencode2`
     * shim when no `opencode` is on PATH).
     */
    data class OpencodeVersionInfo(
        val version: OpencodeVersion,
        val raw: String? = null,
        val binary: String? = null,
    )

    /**
     * Best-effort answer to "is claude installed". [raw] is the trimmed
     * `--version` output (null when the binary never answered); [binary] names
     * the probed executable (`claude`).
     */
    data class ClaudeVersionInfo(
        val version: ClaudeVersion,
        val raw: String? = null,
        val binary: String? = null,
    )

    /** What the caller asked for: install, report-only, or uninstall. */
    data class SetupRequest(
        val agent: Agent = Agent.OPENCODE,
        val scope: Scope = Scope.PROJECT,
        val check: Boolean = false,
        val remove: Boolean = false,
    )

    /** Closed outcome set so the adapter maps exit codes without behaviour. */
    sealed interface SetupOutcome {
        /** The target file now carries the entry (`changed=false` = idempotent no-op). */
        data class Installed(val path: Path, val changed: Boolean) : SetupOutcome

        /** Report-only: whether the entry is present, without touching disk. */
        data class Checked(val path: Path, val installed: Boolean) : SetupOutcome

        /** The entry is gone (`changed=false` = already absent). */
        data class Removed(val path: Path, val changed: Boolean) : SetupOutcome

        /** The target file exists but is not parseable JSON. */
        data class Corrupt(val path: Path, val reason: String) : SetupOutcome

        /** Any IO failure while reading or writing. */
        data class Failed(val path: Path, val reason: String) : SetupOutcome
    }

    fun run(request: SetupRequest): SetupOutcome {
        val path = targetPath(request.agent, request.scope)
        return try {
            when {
                request.check -> SetupOutcome.Checked(path, isInstalledAt(path, request.agent))
                request.remove -> removeAt(request.agent, path)
                else -> installAt(request.agent, path)
            }
        } catch (e: IOException) {
            SetupOutcome.Failed(path, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Resolves the file `--check`/install/remove targets (existing file wins). */
    fun targetPath(scope: Scope): Path = targetPath(Agent.OPENCODE, scope)

    /** Resolves the file `--check`/install/remove targets for [agent] (existing file wins). */
    fun targetPath(agent: Agent, scope: Scope): Path = when (agent) {
        Agent.OPENCODE -> when (scope) {
            Scope.PROJECT -> projectConfigPath(projectDir)
            Scope.SYSTEM -> systemConfigPath(userHome)
        }
        Agent.CLAUDE_CODE -> when (scope) {
            Scope.PROJECT -> claudeProjectConfigPath(projectDir)
            Scope.SYSTEM -> claudeSystemConfigPath(userHome)
        }
        Agent.KILO -> when (scope) {
            Scope.PROJECT -> kiloProjectConfigPath(projectDir)
            Scope.SYSTEM -> kiloSystemConfigPath(userHome)
        }
    }

    private fun installAt(agent: Agent, path: Path): SetupOutcome {
        val current = readRootOrNull(path) ?: return doInstall(agent, path, null)
        val root = current.getOrNull()
            ?: return SetupOutcome.Corrupt(path, current.error ?: "not a JSON object")
        if (isCompleteFor(agent, root)) return SetupOutcome.Installed(path, changed = false)
        return doInstall(agent, path, root)
    }

    private fun removeAt(agent: Agent, path: Path): SetupOutcome {
        if (!Files.isRegularFile(path)) return SetupOutcome.Removed(path, changed = false)
        val current = readRootOrNull(path) ?: return doRemove(agent, path, null)
        val root = current.getOrNull()
            ?: return SetupOutcome.Corrupt(path, current.error ?: "not a JSON object")
        if (!hasEntryFor(agent, root)) return SetupOutcome.Removed(path, changed = false)
        return doRemove(agent, path, root)
    }

    private fun doInstall(agent: Agent, path: Path, root: JsonObject?): SetupOutcome {
        val merged = mergeInstallFor(agent, root)
        writeRoot(path, merged)
        return SetupOutcome.Installed(path, changed = true)
    }

    private fun doRemove(agent: Agent, path: Path, root: JsonObject?): SetupOutcome {
        if (root == null) return SetupOutcome.Removed(path, changed = false)
        writeRoot(path, mergeRemoveFor(agent, root))
        return SetupOutcome.Removed(path, changed = true)
    }

    companion object {
        /** The MCP server name owned by this service in every backend config. */
        const val SERVER_NAME: String = "jdx"

        /** The v1 entry: `jdx mcp` on PATH as a local MCP server under `mcp`. */
        fun desiredEntryV1(): JsonObject = buildJsonObject {
            put("type", "local")
            put("command", JsonArray(listOf(JsonPrimitive("jdx"), JsonPrimitive("mcp"))))
            put("enabled", true)
        }

        /** The v2 entry: same server under `mcp.servers`, `enabled` → `disabled`. */
        fun desiredEntryV2(): JsonObject = buildJsonObject {
            put("type", "local")
            put("command", JsonArray(listOf(JsonPrimitive("jdx"), JsonPrimitive("mcp"))))
            put("disabled", false)
        }

        /** Backwards-compatible alias for the v1 entry (v1 support is deprecated, slated for removal). */
        fun desiredEntry(): JsonObject = desiredEntryV1()

        private val prettyJson: Json = Json { prettyPrint = true; prettyPrintIndent = "  " }

        /**
         * Parses `--agent` case-insensitively (`OpenCode`, `opencode`,
         * `open-code` all match; `Claude Code`, `claude-code`, `claudecode`,
         * `claude` all match Claude Code; `kilo`, `kilo-code`, `kilocode`
         * match Kilo Code). Null when unsupported — the adapter exits 3
         * naming it.
         */
        fun parseAgent(raw: String?): Agent? {
            if (raw == null) return Agent.OPENCODE
            return when (raw.lowercase().replace("-", "").replace("_", "").replace(" ", "")) {
                "opencode" -> Agent.OPENCODE
                "claudecode", "claude" -> Agent.CLAUDE_CODE
                "kilo", "kilocode" -> Agent.KILO
                else -> null
            }
        }

        /** Parses `--scope` case-insensitively. Null means exit 3. */
        fun parseScope(raw: String?): Scope? {
            if (raw == null) return Scope.PROJECT
            return Scope.entries.firstOrNull { it.cliName == raw.lowercase() }
        }

        private val OPENCODE_VERSION_PATTERN =
            Regex("""v?(\d+)\.(\d+)\.(\d+)(?:[-.]([0-9A-Za-z.-]+))?""")

        /**
         * Classifies an `opencode --version` output line. V1 stable prints `1.x`
         * (`opencode 1.18.3`); the v2 beta prints `0.0.0-next-*` / `0.0.0-beta-*`
         * / `0.0.0-dev-*` (a future stable would print `2.x`). Anything else —
         * blank, garbage, a version from an unknown future — is UNKNOWN, never a
         * guess. Pure — example- and property-tested.
         */
        fun parseOpencodeVersion(output: String?): OpencodeVersion {
            if (output.isNullOrBlank()) return OpencodeVersion.UNKNOWN
            val match = OPENCODE_VERSION_PATTERN.find(output) ?: return OpencodeVersion.UNKNOWN
            val major = match.groupValues[1].toIntOrNull() ?: return OpencodeVersion.UNKNOWN
            val qualifier = match.groupValues[4].lowercase()
            return when {
                major == 1 -> OpencodeVersion.V1
                major == 2 -> OpencodeVersion.V2
                major == 0 && (
                    qualifier.contains("next") || qualifier.contains("beta") ||
                        qualifier.contains("dev") || qualifier.contains("alpha") ||
                        qualifier.contains("rc")
                    ) -> OpencodeVersion.V2
                else -> OpencodeVersion.UNKNOWN
            }
        }

        /**
         * Answers "which opencode is installed for use" from PATH. Probes the
         * `opencode` binary first — since v2 replaced the v1 binary in place,
         * that name is what the user runs — and falls back to the legacy
         * `opencode2` shim only when no `opencode` is on PATH. The first binary
         * found wins even when its output is unparseable (UNKNOWN names the raw
         * output; a legacy shim must never shadow the real binary). Never throws:
         * missing binaries read as ABSENT, failing runs as UNKNOWN. Pure IO
         * seam ([ProcessRunner]) so tests inject fakes.
         */
        fun probeOpencodeVersion(
            pathDirs: List<Path>,
            runner: ProcessRunner,
            osName: String = System.getProperty("os.name", ""),
        ): OpencodeVersionInfo {
            for (binary in listOf("opencode", "opencode2")) {
                val executable = pathDirs.firstNotNullOfOrNull { dir ->
                    toolFileNames(binary, osName)
                        .map { dir.resolve(it) }
                        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
                } ?: continue
                val raw = try {
                    val outcome = runner.run(executable, listOf("--version"))
                    (outcome.stdout + "\n" + outcome.stderr).trim().ifEmpty { null }
                } catch (_: Exception) {
                    null
                }
                return OpencodeVersionInfo(parseOpencodeVersion(raw), raw, binary)
            }
            return OpencodeVersionInfo(OpencodeVersion.ABSENT)
        }

        /**
         * One human line naming the detected OpenCode line for `setup` and
         * `doctor` output (`opencode v2 (0.0.0-next-17403)`,
         * `opencode not found on PATH`). Pure — example-tested.
         */
        fun describeVersion(info: OpencodeVersionInfo): String = when (info.version) {
            OpencodeVersion.V1 -> "opencode v1" + (info.raw?.let { " ($it)" } ?: "")
            OpencodeVersion.V2 -> "opencode v2" + (info.raw?.let { " ($it)" } ?: "")
            OpencodeVersion.ABSENT -> "opencode not found on PATH"
            OpencodeVersion.UNKNOWN -> "opencode version unknown" + (info.raw?.let { " ($it)" } ?: "")
        }

        /**
         * Answers "is claude installed for use" from PATH. Probes the `claude`
         * binary only. Never throws: a missing binary reads as ABSENT, a
         * failing or blank run as UNKNOWN. Pure IO seam ([ProcessRunner]) so
         * tests inject fakes.
         */
        fun probeClaudeVersion(
            pathDirs: List<Path>,
            runner: ProcessRunner,
            osName: String = System.getProperty("os.name", ""),
        ): ClaudeVersionInfo {
            val executable = pathDirs.firstNotNullOfOrNull { dir ->
                toolFileNames("claude", osName)
                    .map { dir.resolve(it) }
                    .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            } ?: return ClaudeVersionInfo(ClaudeVersion.ABSENT)
            val raw = try {
                val outcome = runner.run(executable, listOf("--version"))
                (outcome.stdout + "\n" + outcome.stderr).trim().ifEmpty { null }
            } catch (_: Exception) {
                null
            }
            if (raw.isNullOrBlank()) return ClaudeVersionInfo(ClaudeVersion.UNKNOWN, raw, "claude")
            return ClaudeVersionInfo(ClaudeVersion.PRESENT, raw, "claude")
        }

        /**
         * One human line naming the detected Claude Code install for `setup`
         * and `doctor` output (`claude-code (1.0.33)`,
         * `claude-code not found on PATH`). Pure — example-tested.
         */
        fun describeClaudeVersion(info: ClaudeVersionInfo): String = when (info.version) {
            ClaudeVersion.PRESENT -> "claude-code" + (info.raw?.let { " ($it)" } ?: " installed")
            ClaudeVersion.ABSENT -> "claude-code not found on PATH"
            ClaudeVersion.UNKNOWN -> "claude-code version unknown" + (info.raw?.let { " ($it)" } ?: "")
        }

        /**
         * Project target: the nearest `opencode.json`/`opencode.jsonc` walking up
         * from [projectDir] (opencode itself walks up to the worktree root), else
         * a fresh `opencode.json` in [projectDir].
         */
        fun projectConfigPath(projectDir: Path): Path {
            var dir: Path? = projectDir.toAbsolutePath().normalize()
            while (dir != null) {
                val json = dir.resolve("opencode.json")
                val jsonc = dir.resolve("opencode.jsonc")
                if (Files.isRegularFile(json)) return json
                if (Files.isRegularFile(jsonc)) return jsonc
                dir = dir.parent
            }
            return projectDir.toAbsolutePath().normalize().resolve("opencode.json")
        }

        /**
         * System target: the existing global config when present
         * (`opencode.jsonc` first — that is what a real install carries), else a
         * fresh `opencode.json` under `~/.config/opencode`.
         */
        fun systemConfigPath(userHome: Path): Path {
            val dir = userHome.resolve(".config/opencode")
            val jsonc = dir.resolve("opencode.jsonc")
            val json = dir.resolve("opencode.json")
            if (Files.isRegularFile(jsonc)) return jsonc
            if (Files.isRegularFile(json)) return json
            return json
        }

        /**
         * Project target for Claude Code: the nearest `.mcp.json` walking up
         * from [projectDir] (mirrors the OpenCode walk-up), else a fresh
         * `.mcp.json` in [projectDir]. Verified against the documented layout:
         * project `.mcp.json` in the checkout, system `~/.claude.json`.
         */
        fun claudeProjectConfigPath(projectDir: Path): Path {
            var dir: Path? = projectDir.toAbsolutePath().normalize()
            while (dir != null) {
                val candidate = dir.resolve(".mcp.json")
                if (Files.isRegularFile(candidate)) return candidate
                dir = dir.parent
            }
            return projectDir.toAbsolutePath().normalize().resolve(".mcp.json")
        }

        /** System target for Claude Code: the user-global `~/.claude.json`. */
        fun claudeSystemConfigPath(userHome: Path): Path = userHome.resolve(".claude.json")

        /** The Claude Code entry: `jdx mcp` on PATH as a stdio MCP server under `mcpServers`. */
        fun desiredClaudeEntry(): JsonObject = buildJsonObject {
            put("command", "jdx")
            put("args", JsonArray(listOf(JsonPrimitive("mcp"))))
        }

        /**
         * Kilo Code project target: the nearest `kilo.json[c]` walking up from
         * [projectDir], preferring the `.kilo/` variant at each level (that is
         * what the Kilo docs name the cleaner setup, and the `.kilo/` file
         * takes priority when both exist in one directory), else a fresh
         * `.kilo/kilo.json` in [projectDir].
         */
        fun kiloProjectConfigPath(projectDir: Path): Path {
            var dir: Path? = projectDir.toAbsolutePath().normalize()
            while (dir != null) {
                for (candidate in kiloProjectCandidates(dir)) {
                    if (Files.isRegularFile(candidate)) return candidate
                }
                dir = dir.parent
            }
            return projectDir.toAbsolutePath().normalize().resolve(".kilo/kilo.json")
        }

        /** Kilo Code project candidates in priority order within one directory. */
        private fun kiloProjectCandidates(dir: Path): List<Path> = listOf(
            dir.resolve(".kilo/kilo.jsonc"),
            dir.resolve(".kilo/kilo.json"),
            dir.resolve("kilo.jsonc"),
            dir.resolve("kilo.json"),
        )

        /**
         * Kilo Code system target: the existing global config when present
         * (`kilo.jsonc` first — that is what the Kilo docs name), else a
         * fresh `kilo.json` under `~/.config/kilo`.
         */
        fun kiloSystemConfigPath(userHome: Path): Path {
            val dir = userHome.resolve(".config/kilo")
            val jsonc = dir.resolve("kilo.jsonc")
            val json = dir.resolve("kilo.json")
            if (Files.isRegularFile(jsonc)) return jsonc
            if (Files.isRegularFile(json)) return json
            return json
        }

        /** Report-only probe shared by `--check` and the `doctor` setup row. */
        fun isInstalledAt(path: Path): Boolean = isInstalledAt(path, Agent.OPENCODE)

        /** Agent-aware report-only probe shared by `--check` and the `doctor` setup row. */
        fun isInstalledAt(path: Path, agent: Agent): Boolean {
            if (!Files.isRegularFile(path)) return false
            return try {
                val root = readRoot(path).getOrNull() ?: return false
                isInstalledRoot(root, agent)
            } catch (_: IOException) {
                false
            }
        }

        /** True when either the v1 (`mcp.jdx`) or the v2 (`mcp.servers.jdx`) entry wires `jdx mcp`. */
        fun isInstalledRoot(root: JsonObject): Boolean = isInstalledRoot(root, Agent.OPENCODE)

        /** True when [agent]'s owned entries wire `jdx mcp` (OpenCode: v1 or v2; Kilo: `mcp.jdx`; Claude: `mcpServers.jdx`). */
        fun isInstalledRootFor(agent: Agent, root: JsonObject): Boolean = isInstalledRoot(root, agent)

        /**
         * True when an install is a no-op (every owned entry present): OpenCode
         * needs **both** the v1 and v2 entries — a v1-only file is completed
         * with the v2 entry — while Kilo and Claude Code need only their
         * single entry. The report-only probe ([isInstalledRoot]) stays
         * lenient (either OpenCode entry counts).
         */
        private fun isCompleteFor(agent: Agent, root: JsonObject): Boolean = when (agent) {
            Agent.OPENCODE -> isV1Installed(root) && isV2Installed(root)
            Agent.KILO -> isV1Installed(root)
            Agent.CLAUDE_CODE -> isClaudeInstalled(root)
        }

        /** Agent-aware probe: OpenCode checks v1/v2 entries, Claude Code checks `mcpServers.jdx`, Kilo checks `mcp.jdx`. */
        fun isInstalledRoot(root: JsonObject, agent: Agent): Boolean = when (agent) {
            Agent.OPENCODE -> isV1Installed(root) || isV2Installed(root)
            Agent.CLAUDE_CODE -> isClaudeInstalled(root)
            Agent.KILO -> isV1Installed(root)
        }

        /** True when the `mcpServers.jdx` entry is a server whose command runs `jdx mcp`. */
        fun isClaudeInstalled(root: JsonObject): Boolean {
            val entry = root["mcpServers"]?.jsonObjectOrNull()?.get(SERVER_NAME)?.jsonObjectOrNull()
                ?: return false
            return isClaudeJdxEntry(entry)
        }

        /**
         * True when [entry] runs `jdx mcp` in the Claude Code shape
         * (`{"command": "jdx", "args": ["mcp"]}`, the `claude mcp add jdx --
         * jdx mcp` equivalent). Accepts absolute install paths
         * (`~/.local/bin/jdx`, `C:\tools\jdx.exe`) and Windows `PATHEXT`
         * shims (`jdx.exe`/`jdx.cmd`/`jdx.bat`) — that is what a real
         * Windows config carries. Never throws (hostile configs).
         */
        fun isClaudeJdxEntry(entry: JsonObject): Boolean {
            val command = entry["command"]?.jsonPrimitiveOrNull() ?: return false
            if (!isJdxCommand(command)) return false
            val args = entry["args"] as? JsonArray ?: return false
            return args.any { (it as? JsonPrimitive)?.contentOrNull() == "mcp" }
        }

        /**
         * Merges the desired Claude Code entry into [root] (null = fresh file).
         * Every other server under `mcpServers` is preserved.
         */
        fun mergeClaudeInstall(root: JsonObject?): JsonObject {
            val base: MutableMap<String, JsonElement> = root?.toMutableMap() ?: mutableMapOf()
            val servers = root?.get("mcpServers")?.jsonObjectOrNull()?.toMutableMap() ?: mutableMapOf()
            servers[SERVER_NAME] = desiredClaudeEntry()
            base["mcpServers"] = JsonObject(servers)
            return JsonObject(base)
        }

        /**
         * Removes `mcpServers.jdx`; drops an emptied `mcpServers` object to
         * stay tidy.
         */
        fun mergeClaudeRemove(root: JsonObject): JsonObject {
            val base = root.toMutableMap()
            val servers = root["mcpServers"]?.jsonObjectOrNull()?.toMutableMap() ?: return root
            servers.remove(SERVER_NAME)
            if (servers.isEmpty()) base.remove("mcpServers") else base["mcpServers"] = JsonObject(servers)
            return JsonObject(base)
        }

        /**
         * True when [command] names the `jdx` launcher: the base name after
         * the last `/` or `\` (so both POSIX and Windows absolute paths work
         * on every host OS — `Path.of` would treat `\` as a plain character
         * on Linux), minus a Windows executable extension (`.exe`/`.cmd`/`.bat`,
         * case-insensitive, mirroring [toolFileNames]). Pure string ops, so it
         * never throws — not even on NUL bytes that reject `Path.of`.
         */
        internal fun isJdxCommand(command: String): Boolean {
            val base = command.split('/', '\\').last()
            val lower = base.lowercase()
            val stem = when {
                lower.endsWith(".exe") || lower.endsWith(".cmd") || lower.endsWith(".bat") ->
                    base.dropLast(4)
                else -> base
            }
            return stem.equals("jdx", ignoreCase = true)
        }

        /** True when the v1 `mcp.jdx` entry is a local server whose command runs `jdx mcp`. */
        fun isV1Installed(root: JsonObject): Boolean {
            val entry = root["mcp"]?.jsonObjectOrNull()?.get(SERVER_NAME)?.jsonObjectOrNull()
                ?: return false
            return isJdxEntry(entry)
        }

        /** True when the v2 `mcp.servers.jdx` entry is a local server whose command runs `jdx mcp`. */
        fun isV2Installed(root: JsonObject): Boolean {
            val servers = root["mcp"]?.jsonObjectOrNull()?.get("servers")?.jsonObjectOrNull()
                ?: return false
            if (isServerEntry(servers)) return false
            val entry = servers[SERVER_NAME]?.jsonObjectOrNull() ?: return false
            return isJdxEntry(entry)
        }

        /** True when [entry] is a local server whose command runs `jdx mcp`. Never throws (hostile configs). */
        fun isJdxEntry(entry: JsonObject): Boolean {
            if (entry["type"]?.jsonPrimitiveOrNull() != "local") return false
            val command = entry["command"] as? JsonArray ?: return false
            val words = command.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
            if (words.size < 2 || words.last() != "mcp") return false
            // Accept absolute install paths (`~/.local/bin/jdx mcp`,
            // `C:\tools\jdx.exe mcp`) and Windows PATHEXT shims: the last two
            // words name the binary and the subcommand.
            return isJdxCommand(words[words.size - 2])
        }

        /**
         * Merges both desired entries into [root] (null = fresh file with `$schema`).
         * Every other server — in `mcp` and in `mcp.servers` — is preserved.
         */
        fun mergeInstall(root: JsonObject?): JsonObject = mergeInstallFor(Agent.OPENCODE, root)

        /** Merges [agent]'s owned entries into [root] (null = fresh file). */
        fun mergeInstallFor(agent: Agent, root: JsonObject?): JsonObject = when (agent) {
            Agent.OPENCODE -> mergeInstallOpencode(root)
            Agent.CLAUDE_CODE -> mergeClaudeInstall(root)
            Agent.KILO -> mergeInstallKilo(root)
        }

        private fun mergeInstallOpencode(root: JsonObject?): JsonObject {
            val base: MutableMap<String, JsonElement> = root?.toMutableMap() ?: mutableMapOf(
                "\$schema" to JsonPrimitive("https://opencode.ai/config.json"),
            )
            val mcp = root?.get("mcp")?.jsonObjectOrNull()?.toMutableMap() ?: mutableMapOf()
            mcp[SERVER_NAME] = desiredEntryV1()
            val serversRaw = mcp["servers"]
            if (serversRaw is JsonObject && isServerEntry(serversRaw)) {
                // A v1 server literally named "servers": the v2 namespace cannot
                // share the key, so the foreign entry wins untouched and only the
                // v1 entry is written (merge discipline — never clobber).
            } else {
                val servers = (serversRaw as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                servers[SERVER_NAME] = desiredEntryV2()
                mcp["servers"] = JsonObject(servers)
            }
            base["mcp"] = JsonObject(mcp)
            return JsonObject(base)
        }

        /**
         * Merges the single Kilo Code entry (`mcp.jdx`) into [root] (null = fresh
         * file with no `$schema` — that URL names the OpenCode config schema).
         * Every other key — including any `servers` key, which has no OpenCode
         * v2 meaning in a Kilo config — is preserved untouched.
         */
        fun mergeInstallKilo(root: JsonObject?): JsonObject {
            val base: MutableMap<String, JsonElement> = root?.toMutableMap() ?: mutableMapOf()
            val mcp = root?.get("mcp")?.jsonObjectOrNull()?.toMutableMap() ?: mutableMapOf()
            mcp[SERVER_NAME] = desiredEntryV1()
            base["mcp"] = JsonObject(mcp)
            return JsonObject(base)
        }

        /**
         * Removes both `mcp.jdx` and `mcp.servers.jdx`; drops an emptied
         * `mcp.servers` and then an emptied `mcp` object to stay tidy. A v1
         * server literally named `servers` is left untouched (see [mergeInstall]).
         */
        fun mergeRemove(root: JsonObject): JsonObject = mergeRemoveFor(Agent.OPENCODE, root)

        /** Removes [agent]'s owned entries from [root], tidying emptied parents. */
        fun mergeRemoveFor(agent: Agent, root: JsonObject): JsonObject = when (agent) {
            Agent.OPENCODE -> mergeRemoveOpencode(root)
            Agent.CLAUDE_CODE -> mergeClaudeRemove(root)
            Agent.KILO -> mergeRemoveKilo(root)
        }

        private fun mergeRemoveOpencode(root: JsonObject): JsonObject {
            val base = root.toMutableMap()
            val mcp = root["mcp"]?.jsonObjectOrNull()?.toMutableMap() ?: return root
            mcp.remove(SERVER_NAME)
            val serversRaw = mcp["servers"]
            if (serversRaw is JsonObject && !isServerEntry(serversRaw)) {
                val servers = serversRaw.toMutableMap()
                servers.remove(SERVER_NAME)
                if (servers.isEmpty()) mcp.remove("servers") else mcp["servers"] = JsonObject(servers)
            }
            if (mcp.isEmpty()) base.remove("mcp") else base["mcp"] = JsonObject(mcp)
            return JsonObject(base)
        }

        /**
         * Removes the Kilo Code entry (`mcp.jdx`); drops an emptied `mcp`
         * object to stay tidy. Any `servers` key is left untouched — it is
         * not a Kilo-owned namespace.
         */
        fun mergeRemoveKilo(root: JsonObject): JsonObject {
            val base = root.toMutableMap()
            val mcp = root["mcp"]?.jsonObjectOrNull()?.toMutableMap() ?: return root
            mcp.remove(SERVER_NAME)
            if (mcp.isEmpty()) base.remove("mcp") else base["mcp"] = JsonObject(mcp)
            return JsonObject(base)
        }

        /**
         * True when [obj] looks like an MCP server definition rather than the v2
         * `servers` namespace: `type` is a string (`local`/`remote`), where a
         * namespace would hold a server object under that key instead.
         */
        fun isServerEntry(obj: JsonObject): Boolean =
            (obj["type"] as? JsonPrimitive)?.isString == true

        private fun hasEntry(root: JsonObject): Boolean = hasEntryFor(Agent.OPENCODE, root)

        private fun hasEntry(root: JsonObject, agent: Agent): Boolean = hasEntryFor(agent, root)

        private fun hasEntryFor(agent: Agent, root: JsonObject): Boolean = when (agent) {
            Agent.CLAUDE_CODE -> {
                val servers = root["mcpServers"]?.jsonObjectOrNull() ?: return false
                servers.containsKey(SERVER_NAME)
            }
            Agent.KILO -> {
                val mcp = root["mcp"]?.jsonObjectOrNull() ?: return false
                mcp.containsKey(SERVER_NAME)
            }
            Agent.OPENCODE -> {
                val mcp = root["mcp"]?.jsonObjectOrNull() ?: return false
                if (mcp.containsKey(SERVER_NAME)) return true
                val servers = mcp["servers"]?.jsonObjectOrNull() ?: return false
                if (isServerEntry(servers)) return false
                servers.containsKey(SERVER_NAME)
            }
        }

        private fun readRootOrNull(path: Path): RootRead? {
            if (!Files.isRegularFile(path)) return null
            return readRoot(path)
        }

        private fun readRoot(path: Path): RootRead {
            val text = Files.readString(path)
            if (text.isBlank()) return RootRead(JsonObject(emptyMap()))
            val element = try {
                Json.parseToJsonElement(stripJsonc(text))
            } catch (e: Exception) {
                return RootRead(null, e.message ?: e.javaClass.simpleName)
            }
            val root = element as? JsonObject ?: return RootRead(null, "top level is not an object")
            return RootRead(root)
        }

        private fun writeRoot(path: Path, root: JsonObject) {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, prettyJson.encodeToString(JsonObject.serializer(), root) + "\n")
        }

        /**
         * Strips JSONC comments (line and block comments) and trailing
         * commas outside strings so `parseToJsonElement` accepts real-world
         * `opencode.jsonc` files. Single pass, string-aware throughout: a `,`
         * is dropped only when the next non-whitespace, non-comment character
         * outside the string is `}` or `]` — comment-like text inside strings
         * (notably `",]"`) is never touched. Pure — property-tested.
         */
        internal fun stripJsonc(text: String): String {
            val out = StringBuilder(text.length)
            var index = 0
            var inString = false
            while (index < text.length) {
                val char = text[index]
                if (inString) {
                    out.append(char)
                    if (char == '\\' && index + 1 < text.length) {
                        out.append(text[index + 1])
                        index += 2
                        continue
                    }
                    if (char == '"') inString = false
                    index++
                    continue
                }
                when {
                    char == '"' -> {
                        inString = true
                        out.append(char)
                        index++
                    }
                    char == '/' && index + 1 < text.length && text[index + 1] == '/' -> {
                        index += 2
                        while (index < text.length && text[index] != '\n') index++
                    }
                    char == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                        index = skipBlockComment(text, index + 2, out)
                    }
                    char == ',' && isTrailingComma(text, index + 1) -> {
                        index++
                    }
                    else -> {
                        out.append(char)
                        index++
                    }
                }
            }
            return out.toString()
        }

        /** Skips a block comment starting at [from], copying inner newlines so later line-oriented reads stay aligned. */
        private fun skipBlockComment(text: String, from: Int, out: StringBuilder): Int {
            var index = from
            while (index < text.length &&
                !(text[index] == '*' && index + 1 < text.length && text[index + 1] == '/')
            ) {
                if (text[index] == '\n') out.append('\n')
                index++
            }
            return index + 2
        }

        /** True when the next meaningful character from [from] closes an object or array. */
        private fun isTrailingComma(text: String, from: Int): Boolean {
            var index = from
            while (index < text.length) {
                val char = text[index]
                if (char.isWhitespace()) {
                    index++
                    continue
                }
                if (char == '/' && index + 1 < text.length && text[index + 1] == '/') {
                    while (index < text.length && text[index] != '\n') index++
                    continue
                }
                if (char == '/' && index + 1 < text.length && text[index + 1] == '*') {
                    index += 2
                    while (index < text.length &&
                        !(text[index] == '*' && index + 1 < text.length && text[index + 1] == '/')
                    ) {
                        index++
                    }
                    index += 2
                    continue
                }
                return char == '}' || char == ']'
            }
            return false
        }

        private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

        private fun JsonElement.jsonPrimitiveOrNull(): String? =
            (this as? JsonPrimitive)?.contentOrNull()

        private fun JsonPrimitive.contentOrNull(): String? =
            if (isString) content else null
    }

    private data class RootRead(val root: JsonObject?, val error: String? = null) {
        fun getOrNull(): JsonObject? = root
    }
}

/** D-015 mapping for setup: 0 installed/removed/checked-present, 1 checked-absent, 5 corrupt/IO, 3 usage. */
internal fun setupExitCode(outcome: SetupService.SetupOutcome): Int = when (outcome) {
    is SetupService.SetupOutcome.Installed -> 0
    is SetupService.SetupOutcome.Removed -> 0
    is SetupService.SetupOutcome.Checked -> if (outcome.installed) 0 else 1
    is SetupService.SetupOutcome.Corrupt -> 5
    is SetupService.SetupOutcome.Failed -> 5
}
