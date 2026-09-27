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
 * third-party agent configs. This issue lands the OpenCode backend only —
 * other agents follow the same [AgentBackend] seam. All filesystem behaviour
 * lives here; the Clikt command only parses flags, renders, and maps exit
 * codes (D-004).
 *
 * OpenCode layout (verified against a real install: global
 * `~/.config/opencode/opencode.jsonc` with `"$schema":
 * "https://opencode.ai/config.json"`; project `opencode.json`/`opencode.jsonc`
 * in the checkout): MCP servers live under the top-level `mcp` object keyed by
 * server name, each a `{ "type": "local", "command": [...], "enabled": ... }`
 * object (see https://opencode.ai/config.json `McpLocalConfig`). The entry
 * this service owns is `mcp.jdx`.
 *
 * Merge discipline: the target file is parsed leniently (JSONC comments and
 * trailing commas are accepted), every unrelated key is preserved byte-free —
 * only `mcp.jdx` is added, replaced, or removed. A second run is a byte-exact
 * no-op: when the desired entry is already present nothing is rewritten, so
 * comments and formatting survive idempotent re-runs.
 */
class SetupService(
    private val userHome: Path,
    private val projectDir: Path,
) {
    /** Agents with setup support. Only OpenCode ships in this issue. */
    enum class Agent(val cliName: String) {
        OPENCODE("opencode"),
    }

    /** Where the entry is written: the checkout or the user's global config. */
    enum class Scope(val cliName: String) {
        PROJECT("project"),
        SYSTEM("system"),
    }

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
        val path = targetPath(request.scope)
        return try {
            when {
                request.check -> SetupOutcome.Checked(path, isInstalledAt(path))
                request.remove -> removeAt(path)
                else -> installAt(path)
            }
        } catch (e: IOException) {
            SetupOutcome.Failed(path, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Resolves the file `--check`/install/remove targets (existing file wins). */
    fun targetPath(scope: Scope): Path = when (scope) {
        Scope.PROJECT -> projectConfigPath(projectDir)
        Scope.SYSTEM -> systemConfigPath(userHome)
    }

    private fun installAt(path: Path): SetupOutcome {
        val current = readRootOrNull(path) ?: return doInstall(path, null)
        val root = current.getOrNull()
            ?: return SetupOutcome.Corrupt(path, current.error ?: "not a JSON object")
        if (isInstalledRoot(root)) return SetupOutcome.Installed(path, changed = false)
        return doInstall(path, root)
    }

    private fun removeAt(path: Path): SetupOutcome {
        if (!Files.isRegularFile(path)) return SetupOutcome.Removed(path, changed = false)
        val current = readRootOrNull(path) ?: return doRemove(path, null)
        val root = current.getOrNull()
            ?: return SetupOutcome.Corrupt(path, current.error ?: "not a JSON object")
        if (!hasEntry(root)) return SetupOutcome.Removed(path, changed = false)
        return doRemove(path, root)
    }

    private fun doInstall(path: Path, root: JsonObject?): SetupOutcome {
        val merged = mergeInstall(root)
        writeRoot(path, merged)
        return SetupOutcome.Installed(path, changed = true)
    }

    private fun doRemove(path: Path, root: JsonObject?): SetupOutcome {
        if (root == null) return SetupOutcome.Removed(path, changed = false)
        writeRoot(path, mergeRemove(root))
        return SetupOutcome.Removed(path, changed = true)
    }

    companion object {
        /** The MCP server name owned by this service in every backend config. */
        const val SERVER_NAME: String = "jdx"

        /** The exact entry written: `jdx mcp` on PATH as a local MCP server. */
        fun desiredEntry(): JsonObject = buildJsonObject {
            put("type", "local")
            put("command", JsonArray(listOf(JsonPrimitive("jdx"), JsonPrimitive("mcp"))))
            put("enabled", true)
        }

        private val prettyJson: Json = Json { prettyPrint = true; prettyPrintIndent = "  " }

        /**
         * Parses `--agent` case-insensitively (`OpenCode`, `opencode`, `open-code`
         * all match). Null when unsupported — the adapter exits 3 naming it.
         */
        fun parseAgent(raw: String?): Agent? {
            if (raw == null) return Agent.OPENCODE
            return Agent.entries.firstOrNull {
                it.cliName == raw.lowercase().replace("-", "").replace("_", "")
            }
        }

        /** Parses `--scope` case-insensitively. Null means exit 3. */
        fun parseScope(raw: String?): Scope? {
            if (raw == null) return Scope.PROJECT
            return Scope.entries.firstOrNull { it.cliName == raw.lowercase() }
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

        /** Report-only probe shared by `--check` and the `doctor` setup row. */
        fun isInstalledAt(path: Path): Boolean {
            if (!Files.isRegularFile(path)) return false
            return try {
                val root = readRoot(path).getOrNull() ?: return false
                isInstalledRoot(root)
            } catch (_: IOException) {
                false
            }
        }

        /** True when `mcp.jdx` is a local server whose command runs `jdx mcp`. */
        fun isInstalledRoot(root: JsonObject): Boolean {
            val entry = root["mcp"]?.jsonObjectOrNull()?.get(SERVER_NAME)?.jsonObjectOrNull()
                ?: return false
            if (entry["type"]?.jsonPrimitiveOrNull() != "local") return false
            val command = entry["command"] as? JsonArray ?: return false
            val words = command.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
            // Accept absolute install paths (`~/.local/bin/jdx mcp`): the last two
            // words name the binary and the subcommand.
            return words.size >= 2 &&
                words.last() == "mcp" &&
                Path.of(words[words.size - 2]).fileName.toString() == "jdx"
        }

        /** Merges the desired entry into [root] (null = fresh file with `$schema`). */
        fun mergeInstall(root: JsonObject?): JsonObject {
            val base: MutableMap<String, JsonElement> = root?.toMutableMap() ?: mutableMapOf(
                "\$schema" to JsonPrimitive("https://opencode.ai/config.json"),
            )
            val mcp = root?.get("mcp")?.jsonObjectOrNull()?.toMutableMap() ?: mutableMapOf()
            mcp[SERVER_NAME] = desiredEntry()
            base["mcp"] = JsonObject(mcp)
            return JsonObject(base)
        }

        /** Removes only `mcp.jdx`; drops an emptied `mcp` object to stay tidy. */
        fun mergeRemove(root: JsonObject): JsonObject {
            val base = root.toMutableMap()
            val mcp = root["mcp"]?.jsonObjectOrNull()?.toMutableMap() ?: return root
            mcp.remove(SERVER_NAME)
            if (mcp.isEmpty()) base.remove("mcp") else base["mcp"] = JsonObject(mcp)
            return JsonObject(base)
        }

        private fun hasEntry(root: JsonObject): Boolean =
            root["mcp"]?.jsonObjectOrNull()?.containsKey(SERVER_NAME) == true

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
