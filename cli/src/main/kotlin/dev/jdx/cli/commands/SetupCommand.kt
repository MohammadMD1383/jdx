package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.render.JdxJson
import dev.jdx.cli.render.envelopeJson
import dev.jdx.cli.service.SetupService
import dev.jdx.cli.service.setupExitCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * `jdx setup --agent <name> --scope <project|system> [--check] [--remove] [--json]`.
 *
 * First-class agent wiring (issue #33 family): writes, checks, and removes the
 * MCP server entry that launches `jdx mcp`. A thin adapter (D-004): flag
 * parsing, rendering, and exit codes only — [SetupService] owns the merge.
 */
class SetupCommand(
    private val serviceFactory: (userHome: java.nio.file.Path, projectDir: java.nio.file.Path) -> SetupService =
        ::SetupService,
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "setup") {
    override fun help(context: Context): String =
        "Wire jdx into an AI agent: write the MCP server entry that launches `jdx mcp` " +
            "into the agent config (project checkout or user-global). " +
            "--agent opencode (the only backend so far) --scope project|system. " +
            "--check reports without writing; --remove uninstalls cleanly. " +
            "Merges (never clobbers unrelated entries); re-runs are no-ops. " +
            "Exits 0 installed/removed/present, 1 checked-absent, 3 bad usage, 5 unreadable config."

    private val agent by option(
        "--agent",
        help = "Agent to wire (only opencode so far; more backends follow the same seam).",
    )

    private val scope by option(
        "--scope",
        help = "Where to write: project (opencode.json in the checkout, default) or system (~/.config/opencode).",
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
                "usage error: unsupported --agent '${agent}' (only --agent opencode so far)",
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
            message = setupMessage(outcome),
        )
        finish(setupText(outcome, parsedScope), payload, setupExitCode(outcome))
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
    val message: String,
)

private fun SetupPayload.toJson(ok: Boolean): String =
    envelopeJson("setup", ok, JdxJson.encodeToJsonElement(this))

private fun setupPath(outcome: SetupService.SetupOutcome): java.nio.file.Path? = when (outcome) {
    is SetupService.SetupOutcome.Installed -> outcome.path
    is SetupService.SetupOutcome.Checked -> outcome.path
    is SetupService.SetupOutcome.Removed -> outcome.path
    is SetupService.SetupOutcome.Corrupt -> outcome.path
    is SetupService.SetupOutcome.Failed -> outcome.path
}

private fun setupMessage(outcome: SetupService.SetupOutcome): String = when (outcome) {
    is SetupService.SetupOutcome.Installed ->
        if (outcome.changed) "installed (jdx mcp entry written to ${outcome.path.fileName})"
        else "already installed (${outcome.path.fileName})"
    is SetupService.SetupOutcome.Checked ->
        if (outcome.installed) "installed (${outcome.path.fileName})" else "not installed (${outcome.path.fileName})"
    is SetupService.SetupOutcome.Removed ->
        if (outcome.changed) "removed (jdx entry deleted from ${outcome.path.fileName})"
        else "not installed (nothing to remove)"
    is SetupService.SetupOutcome.Corrupt -> "unreadable config ${outcome.path}: ${outcome.reason}"
    is SetupService.SetupOutcome.Failed -> "setup failed: ${outcome.reason}"
}

private fun setupText(outcome: SetupService.SetupOutcome, scope: SetupService.Scope): String {
    val where = if (scope == SetupService.Scope.PROJECT) "project" else "system"
    return when (outcome) {
        is SetupService.SetupOutcome.Installed ->
            if (outcome.changed) {
                "opencode $where setup installed\n  ${outcome.path}\n" +
                    "next: restart opencode (config loads once at startup)"
            } else {
                "opencode $where setup already installed (no changes)\n  ${outcome.path}"
            }
        is SetupService.SetupOutcome.Checked ->
            if (outcome.installed) "opencode $where setup installed\n  ${outcome.path}"
            else "opencode $where setup not installed\n  ${outcome.path}\n" +
                "next: jdx setup --agent opencode --scope ${where.lowercase()}"
        is SetupService.SetupOutcome.Removed ->
            if (outcome.changed) "opencode $where setup removed\n  ${outcome.path}"
            else "opencode $where setup not installed (nothing to remove)\n  ${outcome.path}"
        is SetupService.SetupOutcome.Corrupt ->
            "opencode $where setup unreadable: ${outcome.path}: ${outcome.reason}"
        is SetupService.SetupOutcome.Failed ->
            "opencode $where setup failed: ${outcome.reason}"
    }
}
