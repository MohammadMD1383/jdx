package dev.jdx.cli

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import dev.jdx.cli.commands.DoctorCommand
import dev.jdx.cli.commands.LsCommand
import dev.jdx.cli.commands.cacheGroup
import dev.jdx.cli.commands.BodyCommand
import dev.jdx.cli.commands.CallersCommand
import dev.jdx.cli.commands.CallsCommand
import dev.jdx.cli.commands.SamplesCommand
import dev.jdx.cli.commands.DocCommand
import dev.jdx.cli.commands.HierarchyCommand
import dev.jdx.cli.commands.ImplementorsCommand
import dev.jdx.cli.commands.SignatureCommand
import dev.jdx.cli.commands.SourceCommand
import dev.jdx.cli.commands.MembersCommand
import dev.jdx.cli.commands.OutlineCommand
import dev.jdx.cli.commands.ResolveCommand
import dev.jdx.cli.commands.SearchCommand
import dev.jdx.cli.commands.ShowCommand
import dev.jdx.cli.commands.TreeCommand
import dev.jdx.cli.commands.UsagesCommand
import dev.jdx.cli.commands.VersionCommand
import dev.jdx.cli.commands.cacheGroup
import dev.jdx.cli.commands.wsGroup

/**
 * Root of the `jdx` command tree. A thin adapter (D-004): it wires options and subcommands
 * only — every behaviour a subcommand exposes lives in `core`/`index`/`sources`/`decompile`
 * behind `JdxService`. Subcommands register here as their milestones land.
 *
 * Extends Clikt's plain `CoreCliktCommand` (no mordant/JNA — see cli/build.gradle.kts).
 */
class JdxCli : CoreCliktCommand(name = "jdx") {
    init {
        versionOption(BuildInfo.version)
    }

    val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, " +
            "D-007). Accepted before or after the subcommand name (D-027).",
    ).flag()

    val workspace by option(
        "-w",
        "--workspace",
        help = "Use a named workspace (jdx ws create) for this query. Accepted before or " +
            "after the subcommand name, like --json (D-027).",
    )

    override fun run() = Unit
}

/**
 * Walks the context chain to the root [JdxCli]. One level (`parent?.command`)
 * is enough for flat commands (`doctor`), but group members (`ws list`,
 * `cache info`) sit two levels down — stopping at the first parent silently
 * drops root flags for exactly those commands (found via T-018 e2e).
 */
internal fun CoreCliktCommand.rootCommand(): JdxCli? {
    var context = currentContext.parent
    while (context != null) {
        (context.command as? JdxCli)?.let { return it }
        context = context.parent
    }
    return null
}

/**
 * Effective workspace: the command's own `-w` or the root's (`jdx -w mc members ...`
 * and `jdx members -w mc ...` are equivalent). `JDX_WORKSPACE` and `jdx ws use` fill
 * in when neither flag is given (PROPOSAL.md §13).
 */
internal fun CoreCliktCommand.effectiveWorkspace(ownWorkspace: String?): String? =
    ownWorkspace ?: rootCommand()?.workspace

/**
 * Effective `--json`: the command's own flag or the root's. `--json` is accepted in both
 * positions (`jdx --json doctor`, `jdx doctor --json`); per-command flags are the mechanism
 * until true global-flag plumbing lands with the T-011 flag pass (D-027).
 */
internal fun CoreCliktCommand.effectiveJson(ownJson: Boolean): Boolean =
    ownJson || rootCommand()?.json == true

/** One-shot CLI entry point; the fat jar's `Main-Class` (see `app/build.gradle.kts`). */
fun main(args: Array<String>): Unit =
    JdxCli().subcommands(
        VersionCommand(),
        DoctorCommand(),
        ShowCommand(),
        MembersCommand(),
        OutlineCommand(),
        BodyCommand(),
        SourceCommand(),
        SignatureCommand(),
        DocCommand(),
        SearchCommand(),
        ResolveCommand(),
        LsCommand(),
        TreeCommand(),
        UsagesCommand(),
        HierarchyCommand(),
        ImplementorsCommand(),
        CallersCommand(),
        CallsCommand(),
        SamplesCommand(),
        wsGroup(),
        cacheGroup(),
    ).main(args)
