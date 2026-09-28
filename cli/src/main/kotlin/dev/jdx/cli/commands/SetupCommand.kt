package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.render.JdxJson
import dev.jdx.cli.render.envelopeJson
import dev.jdx.cli.service.RealProcessRunner
import dev.jdx.cli.service.SetupService
import dev.jdx.cli.service.setupExitCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * `jdx setup --agent <name> --scope <project|system> [--check] [--remove] [--json]`.
 *
 * First-class agent wiring (issue #33 family): writes, checks, and removes the
 * MCP server entries that launch `jdx mcp`. OpenCode v1 (`mcp.jdx`) and v2
 * (`mcp.servers.jdx`) entries are always written together so the config works
 * whichever line is installed; Claude Code gets the `mcpServers.jdx`
 * (`{"command": "jdx", "args": ["mcp"]}`, the `claude mcp add jdx -- jdx mcp`
 * equivalent) entry in `.mcp.json` (project) or `~/.claude.json` (system);
 * Kilo Code (an OpenCode fork sharing the `mcp` map shape) gets the single
 * `mcp.jdx` entry in its `kilo.json[c]` files; Codex CLI gets the
 * `[mcp_servers.jdx]` table (`command = "jdx"`, `args = ["mcp"]`, the
 * `codex mcp add jdx -- jdx mcp` equivalent) in `.codex/config.toml`
 * (project, trusted checkouts) or `~/.codex/config.toml` (system,
 * `$CODEX_HOME` honoured); GitHub Copilot CLI gets the `mcpServers.jdx`
 * (`{"type": "local", "command": "jdx", "args": ["mcp"]}`, the
 * `copilot mcp add jdx -- jdx mcp` equivalent) entry in `.mcp.json`
 * (project) or `~/.copilot/mcp-config.json` (system, `$COPILOT_HOME`
 * honoured). The detected line is reported,
 * never assumed. A thin adapter (D-004): flag parsing, rendering, and exit
 * codes only — [SetupService] owns the merge and the version classification.
 */
class SetupCommand(
    private val serviceFactory: (userHome: java.nio.file.Path, projectDir: java.nio.file.Path) -> SetupService =
        ::SetupService,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val versionProbe: () -> SetupService.OpencodeVersionInfo = ::systemOpencodeVersion,
    private val claudeVersionProbe: () -> SetupService.ClaudeVersionInfo = ::systemClaudeVersion,
    private val codexVersionProbe: () -> SetupService.CodexVersionInfo = ::systemCodexVersion,
    private val copilotVersionProbe: () -> SetupService.CopilotVersionInfo = ::systemCopilotVersion,
) : CoreCliktCommand(name = "setup") {
    override fun help(context: Context): String =
        "Wire jdx into an AI agent: write the MCP server entries that launch `jdx mcp` " +
            "into the agent config (project checkout or user-global). " +
            "--agent opencode|claude-code|kilo|codex|copilot --scope project|system. " +
            "OpenCode writes both the v1 (mcp.jdx) and v2 (mcp.servers.jdx) entries so either " +
            "OpenCode line picks it up (v1 support is deprecated, slated for removal); " +
            "Claude Code writes the mcpServers.jdx entry into .mcp.json (project) or " +
            "~/.claude.json (system); " +
            "Kilo Code gets the single mcp.jdx entry in kilo.jsonc (project prefers .kilo/); " +
            "Codex CLI gets the [mcp_servers.jdx] table in .codex/config.toml (project) or " +
            "~/.codex/config.toml (system, \$CODEX_HOME honoured); " +
            "GitHub Copilot CLI gets the mcpServers.jdx entry in .mcp.json (project) or " +
            "~/.copilot/mcp-config.json (system, \$COPILOT_HOME honoured). " +
            "--check reports without writing; --remove uninstalls cleanly. " +
            "Merges (never clobbers unrelated entries); re-runs are no-ops. " +
            "Exits 0 installed/removed/present, 1 checked-absent, 3 bad usage, 5 unreadable config."

    private val agent by option(
        "--agent",
        help = "Agent to wire: opencode, claude-code (claude is accepted as an alias), kilo (Kilo Code, an OpenCode fork), codex (Codex CLI), or copilot (GitHub Copilot CLI).",
    )

    private val scope by option(
        "--scope",
        help = "Where to write: project (default) or system. " +
            "OpenCode targets opencode.json[c] (~/.config/opencode); " +
            "Claude Code targets .mcp.json (project) or ~/.claude.json (system); " +
            "Kilo Code targets kilo.json[c] (~/.config/kilo, project prefers .kilo/); " +
            "Codex CLI targets .codex/config.toml (project) or ~/.codex/config.toml (system, \$CODEX_HOME honoured); " +
            "GitHub Copilot CLI targets .mcp.json (project) or ~/.copilot/mcp-config.json (system, \$COPILOT_HOME honoured).",
    )

    private val checkOnly by option(
        "--check",
        help = "Report whether the entry is present without writing anything.",
    ).flag()

    private val remove by option(
        "--remove",
        help = "Remove the jdx MCP entry, leaving every other entry untouched.",
    ).flag()

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val parsedAgent = SetupService.parseAgent(agent)
        if (parsedAgent == null) {
            finish(
                "usage error: unsupported --agent '${agent}' (only --agent opencode|claude-code|kilo|codex|copilot)",
                SetupPayload(agent = agent, message = "unsupported agent '${agent}'"),
                exitCode = 3,
            )
            return
        }
        val parsedScope = SetupService.parseScope(scope)
        if (parsedScope == null) {
            finish(
                "usage error: --scope must be project or system (was '${scope}')",
                SetupPayload(agent = parsedAgent.cliName, scope = scope, message = "bad scope '${scope}'"),
                exitCode = 3,
            )
            return
        }
        if (checkOnly && remove) {
            finish(
                "usage error: --check and --remove are mutually exclusive",
                SetupPayload(
                    agent = parsedAgent.cliName,
                    scope = parsedScope.cliName,
                    message = "--check and --remove are mutually exclusive",
                ),
                exitCode = 3,
            )
            return
        }
        val service = serviceFactory(
            Paths.get(System.getProperty("user.home")),
            Paths.get("").toAbsolutePath(),
        )
        val outcome = service.run(
            SetupService.SetupRequest(
                agent = parsedAgent,
                scope = parsedScope,
                check = checkOnly,
                remove = remove,
            ),
        )
        val path = setupPath(outcome)
        // Version probes are display-only per backend; Kilo Code skips both
        // (no `opencode --version` classification to report, no binary probe).
        val opencodeVersion = if (parsedAgent == SetupService.Agent.OPENCODE) versionProbe() else null
        val claudeVersion = if (parsedAgent == SetupService.Agent.CLAUDE_CODE) claudeVersionProbe() else null
        val codexVersion = if (parsedAgent == SetupService.Agent.CODEX) codexVersionProbe() else null
        val copilotVersion = if (parsedAgent == SetupService.Agent.COPILOT) copilotVersionProbe() else null
        val detected = when (parsedAgent) {
            SetupService.Agent.OPENCODE ->
                SetupService.describeVersion(requireNotNull(opencodeVersion))
            SetupService.Agent.CLAUDE_CODE ->
                SetupService.describeClaudeVersion(requireNotNull(claudeVersion))
            SetupService.Agent.CODEX ->
                SetupService.describeCodexVersion(requireNotNull(codexVersion))
            SetupService.Agent.COPILOT ->
                SetupService.describeCopilotVersion(requireNotNull(copilotVersion))
            SetupService.Agent.KILO -> ""
        }
        val payload = SetupPayload(
            agent = parsedAgent.cliName,
            scope = parsedScope.cliName,
            path = path?.toString(),
            installed = when (outcome) {
                is SetupService.SetupOutcome.Installed -> true
                is SetupService.SetupOutcome.Checked -> outcome.installed
                is SetupService.SetupOutcome.Removed -> false
                is SetupService.SetupOutcome.Corrupt -> null
                is SetupService.SetupOutcome.Failed -> null
            },
            changed = when (outcome) {
                is SetupService.SetupOutcome.Installed -> outcome.changed
                is SetupService.SetupOutcome.Removed -> outcome.changed
                else -> null
            },
            opencodeVersion = opencodeVersion?.version?.cliName,
            opencodeRaw = opencodeVersion?.raw,
            claudeVersion = claudeVersion?.version?.cliName,
            claudeRaw = claudeVersion?.raw,
            codexVersion = codexVersion?.version?.cliName,
            codexRaw = codexVersion?.raw,
            copilotVersion = copilotVersion?.version?.cliName,
            copilotRaw = copilotVersion?.raw,
            message = setupMessage(outcome, parsedAgent),
        )
        finish(setupText(outcome, parsedAgent, parsedScope, detected), payload, setupExitCode(outcome))
    }

    private fun finish(text: String, payload: SetupPayload, exitCode: Int) {
        if (effectiveJson(json)) {
            println(payload.toJson(exitCode == 0))
        } else {
            println(text)
        }
        if (exitCode != 0) terminate(exitCode)
    }
}

@Serializable
private data class SetupPayload(
    val agent: String? = null,
    val scope: String? = null,
    val path: String? = null,
    val installed: Boolean? = null,
    val changed: Boolean? = null,
    /** Detected OpenCode line (`v1`, `v2`, `absent`, `unknown`) — the install itself always covers both. */
    val opencodeVersion: String? = null,
    /** Trimmed `opencode --version` output (null when the binary never answered). */
    val opencodeRaw: String? = null,
    /** Detected Claude Code presence (`present`, `absent`, `unknown`). */
    val claudeVersion: String? = null,
    /** Trimmed `claude --version` output (null when the binary never answered). */
    val claudeRaw: String? = null,
    /** Detected Codex CLI presence (`present`, `absent`, `unknown`). */
    val codexVersion: String? = null,
    /** Trimmed `codex --version` output (null when the binary never answered). */
    val codexRaw: String? = null,
    /** Detected GitHub Copilot CLI presence (`present`, `absent`, `unknown`). */
    val copilotVersion: String? = null,
    /** Trimmed `copilot --version` output (null when the binary never answered). */
    val copilotRaw: String? = null,
    val message: String,
)

/** Best-effort host probe for display only: PATH `opencode`/`opencode2` classification, never throws. */
private fun systemOpencodeVersion(): SetupService.OpencodeVersionInfo {
    val pathDirs = System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.filter { it.isNotEmpty() }
        ?.map { Paths.get(it) }
        ?: emptyList()
    return SetupService.probeOpencodeVersion(pathDirs, RealProcessRunner)
}

/** Best-effort host probe for display only: PATH `claude` presence, never throws. */
private fun systemClaudeVersion(): SetupService.ClaudeVersionInfo {
    val pathDirs = System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.filter { it.isNotEmpty() }
        ?.map { Paths.get(it) }
        ?: emptyList()
    return SetupService.probeClaudeVersion(pathDirs, RealProcessRunner)
}

/** Best-effort host probe for display only: PATH `codex` presence, never throws. */
private fun systemCodexVersion(): SetupService.CodexVersionInfo {
    val pathDirs = System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.filter { it.isNotEmpty() }
        ?.map { Paths.get(it) }
        ?: emptyList()
    return SetupService.probeCodexVersion(pathDirs, RealProcessRunner)
}

/** Best-effort host probe for display only: PATH `copilot` presence, never throws. */
private fun systemCopilotVersion(): SetupService.CopilotVersionInfo {
    val pathDirs = System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.filter { it.isNotEmpty() }
        ?.map { Paths.get(it) }
        ?: emptyList()
    return SetupService.probeCopilotVersion(pathDirs, RealProcessRunner)
}
private fun SetupPayload.toJson(ok: Boolean): String =
    envelopeJson("setup", ok, JdxJson.encodeToJsonElement(this))

private fun setupPath(outcome: SetupService.SetupOutcome): java.nio.file.Path? = when (outcome) {
    is SetupService.SetupOutcome.Installed -> outcome.path
    is SetupService.SetupOutcome.Checked -> outcome.path
    is SetupService.SetupOutcome.Removed -> outcome.path
    is SetupService.SetupOutcome.Corrupt -> outcome.path
    is SetupService.SetupOutcome.Failed -> outcome.path
}

private fun setupMessage(outcome: SetupService.SetupOutcome, agent: SetupService.Agent): String = when (outcome) {
    is SetupService.SetupOutcome.Installed ->
        when (agent) {
            SetupService.Agent.OPENCODE ->
                if (outcome.changed) "installed (v1+v2 jdx mcp entries written to ${outcome.path.fileName})"
                else "already installed (${outcome.path.fileName}, v1+v2 entries)"
            SetupService.Agent.CLAUDE_CODE ->
                if (outcome.changed) "installed (mcpServers jdx mcp entry written to ${outcome.path.fileName})"
                else "already installed (${outcome.path.fileName}, mcpServers entry)"
            SetupService.Agent.KILO ->
                if (outcome.changed) "installed (jdx mcp entry written to ${outcome.path.fileName})"
                else "already installed (${outcome.path.fileName})"
            SetupService.Agent.CODEX ->
                if (outcome.changed) "installed (mcp_servers jdx mcp entry written to ${outcome.path.fileName})"
                else "already installed (${outcome.path.fileName}, mcp_servers entry)"
            SetupService.Agent.COPILOT ->
                if (outcome.changed) "installed (mcpServers jdx mcp entry written to ${outcome.path.fileName})"
                else "already installed (${outcome.path.fileName}, mcpServers entry)"
        }
    is SetupService.SetupOutcome.Checked ->
        if (outcome.installed) "installed (${outcome.path.fileName})" else "not installed (${outcome.path.fileName})"
    is SetupService.SetupOutcome.Removed ->
        when (agent) {
            SetupService.Agent.KILO ->
                if (outcome.changed) "removed (jdx entry deleted from ${outcome.path.fileName})"
                else "not installed (nothing to remove)"
            else ->
                if (outcome.changed) "removed (jdx entries deleted from ${outcome.path.fileName})"
                else "not installed (nothing to remove)"
        }
    is SetupService.SetupOutcome.Corrupt -> "unreadable config ${outcome.path}: ${outcome.reason}"
    is SetupService.SetupOutcome.Failed -> "setup failed: ${outcome.reason}"
}

private fun setupText(
    outcome: SetupService.SetupOutcome,
    agent: SetupService.Agent,
    scope: SetupService.Scope,
    detectedVersion: String,
): String {
    if (agent == SetupService.Agent.KILO) return setupKiloText(outcome, scope)
    val name = agent.cliName
    val where = if (scope == SetupService.Scope.PROJECT) "project" else "system"
    val detected = "detected: $detectedVersion"
    // OpenCode keeps its exact historical wording (pinned by tests); Claude
    // Code and Codex CLI mirror it with their entry shape and restart hint.
    val installedChanged = when (agent) {
        SetupService.Agent.OPENCODE -> "$name $where setup installed (v1+v2 entries)"
        SetupService.Agent.CLAUDE_CODE -> "$name $where setup installed (mcpServers entry)"
        SetupService.Agent.CODEX -> "$name $where setup installed (mcp_servers entry)"
        SetupService.Agent.COPILOT -> "$name $where setup installed (mcpServers entry)"
        SetupService.Agent.KILO -> "$name $where setup installed"
    }
    return when (outcome) {
        is SetupService.SetupOutcome.Installed ->
            if (outcome.changed) {
                "$installedChanged\n  ${outcome.path}\n  $detected\n" +
                    "next: restart $name (config loads once at startup)"
            } else {
                "$name $where setup already installed (no changes)\n  ${outcome.path}\n  $detected"
            }
        is SetupService.SetupOutcome.Checked ->
            if (outcome.installed) "$name $where setup installed\n  ${outcome.path}\n  $detected"
            else "$name $where setup not installed\n  ${outcome.path}\n  $detected\n" +
                "next: jdx setup --agent $name --scope ${where.lowercase()}"
        is SetupService.SetupOutcome.Removed ->
            if (outcome.changed) "$name $where setup removed\n  ${outcome.path}\n  $detected"
            else "$name $where setup not installed (nothing to remove)\n  ${outcome.path}\n  $detected"
        is SetupService.SetupOutcome.Corrupt ->
            "$name $where setup unreadable: ${outcome.path}: ${outcome.reason}"
        is SetupService.SetupOutcome.Failed ->
            "$name $where setup failed: ${outcome.reason}"
    }
}

/** Kilo Code rendering: the single `mcp.jdx` entry, no OpenCode line probe. */
private fun setupKiloText(
    outcome: SetupService.SetupOutcome,
    scope: SetupService.Scope,
): String {
    val where = if (scope == SetupService.Scope.PROJECT) "project" else "system"
    return when (outcome) {
        is SetupService.SetupOutcome.Installed ->
            if (outcome.changed) {
                "kilo $where setup installed\n  ${outcome.path}\n" +
                    "next: restart Kilo Code (config loads once at startup)"
            } else {
                "kilo $where setup already installed (no changes)\n  ${outcome.path}"
            }
        is SetupService.SetupOutcome.Checked ->
            if (outcome.installed) "kilo $where setup installed\n  ${outcome.path}"
            else "kilo $where setup not installed\n  ${outcome.path}\n" +
                "next: jdx setup --agent kilo --scope ${where.lowercase()}"
        is SetupService.SetupOutcome.Removed ->
            if (outcome.changed) "kilo $where setup removed\n  ${outcome.path}"
            else "kilo $where setup not installed (nothing to remove)\n  ${outcome.path}"
        is SetupService.SetupOutcome.Corrupt ->
            "kilo $where setup unreadable: ${outcome.path}: ${outcome.reason}"
        is SetupService.SetupOutcome.Failed ->
            "kilo $where setup failed: ${outcome.reason}"
    }
}
