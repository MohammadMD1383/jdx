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
 * MCP server entries that launch `jdx mcp`. OpenCode gets both the v1
 * (`mcp.jdx`) and v2 (`mcp.servers.jdx`) entries so the config works whichever
 * line is installed; the detected line is reported, never assumed. Kilo Code
 * (an OpenCode fork sharing the `mcp` map shape) gets the single `mcp.jdx`
 * entry in its `kilo.json[c]` files. A thin adapter (D-004): flag parsing,
 * rendering, and exit codes only — [SetupService] owns the merge and the
 * version classification.
 */
class SetupCommand(
    private val serviceFactory: (userHome: java.nio.file.Path, projectDir: java.nio.file.Path) -> SetupService =
        ::SetupService,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val versionProbe: () -> SetupService.OpencodeVersionInfo = ::systemOpencodeVersion,
) : CoreCliktCommand(name = "setup") {
    override fun help(context: Context): String =
        "Wire jdx into an AI agent: write the MCP server entries that launch `jdx mcp` " +
            "into the agent config (project checkout or user-global). " +
            "--agent opencode|kilo --scope project|system. " +
            "OpenCode gets both the v1 (mcp.jdx) and v2 (mcp.servers.jdx) entries so either " +
            "OpenCode line picks it up (v1 support is deprecated, slated for removal); " +
            "Kilo Code gets the single mcp.jdx entry in kilo.jsonc (project prefers .kilo/). " +
            "--check reports without writing; --remove uninstalls cleanly. " +
            "Merges (never clobbers unrelated entries); re-runs are no-ops. " +
            "Exits 0 installed/removed/present, 1 checked-absent, 3 bad usage, 5 unreadable config."

    private val agent by option(
        "--agent",
        help = "Agent to wire: opencode or kilo (Kilo Code, an OpenCode fork).",
    )

    private val scope by option(
        "--scope",
        help = "Where to write: project (default) or system. " +
            "OpenCode targets opencode.json[c] (~/.config/opencode); " +
            "Kilo Code targets kilo.json[c] (~/.config/kilo, project prefers .kilo/).",
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
                "usage error: unsupported --agent '${agent}' (only --agent opencode|kilo)",
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
        // The OpenCode line probe is display-only for the OpenCode backend;
        // Kilo Code skips it (no `opencode --version` classification to report).
        val version = if (parsedAgent == SetupService.Agent.KILO) null else versionProbe()
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
            opencodeVersion = version?.version?.cliName,
            opencodeRaw = version?.raw,
            message = setupMessage(outcome, parsedAgent),
        )
        finish(setupText(outcome, parsedAgent, parsedScope, version), payload, setupExitCode(outcome))
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

private fun SetupPayload.toJson(ok: Boolean): String =
    envelopeJson("setup", ok, JdxJson.encodeToJsonElement(this))

private fun setupPath(outcome: SetupService.SetupOutcome): java.nio.file.Path? = when (outcome) {
    is SetupService.SetupOutcome.Installed -> outcome.path
    is SetupService.SetupOutcome.Checked -> outcome.path
    is SetupService.SetupOutcome.Removed -> outcome.path
    is SetupService.SetupOutcome.Corrupt -> outcome.path
    is SetupService.SetupOutcome.Failed -> outcome.path
}

private fun setupMessage(outcome: SetupService.SetupOutcome, agent: SetupService.Agent): String {
    val entries = if (agent == SetupService.Agent.KILO) "jdx mcp entry" else "v1+v2 jdx mcp entries"
    return when (outcome) {
        is SetupService.SetupOutcome.Installed ->
            if (outcome.changed) "installed ($entries written to ${outcome.path.fileName})"
            else if (agent == SetupService.Agent.KILO) "already installed (${outcome.path.fileName})"
            else "already installed (${outcome.path.fileName}, v1+v2 entries)"
        is SetupService.SetupOutcome.Checked ->
            if (outcome.installed) "installed (${outcome.path.fileName})" else "not installed (${outcome.path.fileName})"
        is SetupService.SetupOutcome.Removed ->
            if (outcome.changed) {
                if (agent == SetupService.Agent.KILO) "removed (jdx entry deleted from ${outcome.path.fileName})"
                else "removed (jdx entries deleted from ${outcome.path.fileName})"
            } else "not installed (nothing to remove)"
        is SetupService.SetupOutcome.Corrupt -> "unreadable config ${outcome.path}: ${outcome.reason}"
        is SetupService.SetupOutcome.Failed -> "setup failed: ${outcome.reason}"
    }
}

private fun setupText(
    outcome: SetupService.SetupOutcome,
    agent: SetupService.Agent,
    scope: SetupService.Scope,
    version: SetupService.OpencodeVersionInfo?,
): String {
    val name = agent.cliName
    if (agent == SetupService.Agent.KILO) return setupKiloText(outcome, scope)
    val where = if (scope == SetupService.Scope.PROJECT) "project" else "system"
    val detected = "detected: ${SetupService.describeVersion(requireNotNull(version))}"
    return when (outcome) {
        is SetupService.SetupOutcome.Installed ->
            if (outcome.changed) {
                "$name $where setup installed (v1+v2 entries)\n  ${outcome.path}\n  $detected\n" +
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
