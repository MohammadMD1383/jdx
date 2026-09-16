package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.render.JdxJson
import dev.jdx.cli.render.envelopeJson
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceCorruptException
import dev.jdx.index.workspace.WorkspaceDefinition
import dev.jdx.index.workspace.WorkspaceStore
import dev.jdx.index.workspace.validateWorkspaceName
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.system.exitProcess

/**
 * `jdx ws ...` — named workspace management (T-015, PROPOSAL.md §7.4).
 *
 * A workspace is an ordered list of binary roots stored as TOML in
 * `~/.config/jdx/workspaces/<name>.toml`. Read commands consume it via `-w <name>`
 * (or `JDX_WORKSPACE`, or the `jdx ws use` default); explicit `--jars` always merge
 * in front of it, and the first root providing a class wins (`DUPLICATE_FQN`
 * otherwise — the shadowing itself lives in `JdxService`, this group only stores order).
 *
 * Thin by rule (D-004): every behaviour here is CRUD on [WorkspaceStore] plus argument
 * validation. Exit codes: 0 ok · 1 not found / already exists · 3 usage error ·
 * 4 corrupt workspace file · 6 IO failure (D-015).
 */
class WsCommand : CoreCliktCommand(name = "ws") {
    override fun help(context: Context): String =
        "Manage named workspaces: ordered jar roots reused across queries " +
            "(create, list, info, remove, use, add). " +
            "Read commands consume them via -w <name>, JDX_WORKSPACE, or jdx ws use."

    override fun run() = Unit
}

/** Builds the `ws` group with its subcommands, sharing one store (production default). */
fun wsGroup(
    store: WorkspaceStore = FileWorkspaceStore.system(),
    terminate: (Int) -> Nothing = ::exitProcess,
): WsCommand = WsCommand().subcommands(
    WsCreateCommand(store, terminate),
    WsListCommand(store, terminate),
    WsInfoCommand(store, terminate),
    WsRemoveCommand(store, terminate),
    WsUseCommand(store, terminate),
    WsAddCommand(store, terminate),
)

/** Test seam: an isolated store that never touches the real home directory. */
fun testWsGroup(
    terminate: (Int) -> Nothing = { throw WsExit(it) },
): Pair<WsCommand, InMemoryWorkspaceStore> {
    val store = InMemoryWorkspaceStore()
    return wsGroup(store, terminate) to store
}

/** Thrown by [testWsGroup]'s terminator instead of killing the test JVM (L-026). */
class WsExit(val code: Int) : RuntimeException()

@Serializable
private data class WsPayload(
    val workspace: String? = null,
    val jars: List<String> = emptyList(),
    val includeJdk: Boolean = true,
    val active: Boolean = false,
    val workspaces: List<String> = emptyList(),
    val message: String,
)

private fun WsPayload.toJson(command: String, ok: Boolean): String =
    envelopeJson(command, ok, JdxJson.encodeToJsonElement(this))

/** Prints text + optional JSON, then terminates on non-zero exit (L-026). */
private fun finishWs(
    text: String,
    payload: WsPayload,
    command: String,
    json: Boolean,
    exitCode: Int,
    terminate: (Int) -> Nothing,
) {
    if (json) {
        println(payload.toJson(command, ok = exitCode == 0))
    } else {
        println(text)
    }
    if (exitCode != 0) terminate(exitCode)
}

/** Maps a store failure to its exit: corrupt file → 4, anything else → 6. */
private fun storeExit(e: IOException): Int = if (e is WorkspaceCorruptException) 4 else 6

private fun corruptOrIoMessage(action: String, name: String, e: IOException): String =
    if (e is WorkspaceCorruptException) {
        e.message ?: "workspace '$name' is corrupt"
    } else {
        "cannot $action workspace '$name': ${e.message}"
    }

/**
 * `jdx ws create <name> [--jars <path>...] [--jdk/--no-jdk]`.
 *
 * Refuses when the name exists (exit 1) rather than silently overwriting ordered roots
 * the user crafted — `ws add` extends, `ws remove` clears the way. `--src`/`--coord`
 * fail naming the owning task instead of storing roots no reader honours yet.
 */
class WsCreateCommand(
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "create") {
    override fun help(context: Context): String =
        "Create a named workspace: an ordered list of binary roots reused via -w <name>. " +
            "Refuses when the name exists (see ws add, ws remove). " +
            "Exits 1 when the name exists, 3 on bad usage, 6 on IO failure."

    private val name by argument(help = "Workspace name (letters, digits, '.', '_' and '-').")

    private val jars by option(
        "--jars",
        help = "Binary roots: jar files, class directories or globs (repeatable, order kept).",
    ).multiple()

    private val jdk by option("--jdk", help = "Include the JDK stdlib (the default).").flag()

    private val noJdk by option("--no-jdk", help = "Do not include the JDK stdlib.").flag()

    private val src by option(
        "--src",
        help = "Source dirs (not yet implemented, T-031).",
    ).multiple()

    private val coord by option(
        "--coord",
        help = "Maven coordinates (not yet implemented, T-019).",
    ).multiple()

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        validateWorkspaceName(name)?.let {
            finishWs(
                "usage error: invalid workspace name '$name': $it",
                WsPayload(workspace = name, message = "invalid workspace name '$name': $it"),
                "ws create", json, 3, terminate,
            )
            return
        }
        if (jdk && noJdk) {
            finishWs(
                "usage error: --jdk and --no-jdk are mutually exclusive",
                WsPayload(workspace = name, message = "--jdk and --no-jdk are mutually exclusive"),
                "ws create", json, 3, terminate,
            )
            return
        }
        if (src.isNotEmpty()) {
            finishWs(
                "usage error: --src is not yet implemented (T-031: project source-dir roots)",
                WsPayload(workspace = name, message = "--src is not yet implemented (T-031)"),
                "ws create", json, 3, terminate,
            )
            return
        }
        if (coord.isNotEmpty()) {
            finishWs(
                "usage error: --coord is not yet implemented (T-019: Maven coordinate resolution)",
                WsPayload(workspace = name, message = "--coord is not yet implemented (T-019)"),
                "ws create", json, 3, terminate,
            )
            return
        }
        val definition = WorkspaceDefinition(name, jars, includeJdk = !noJdk)
        try {
            if (store.load(name) != null) {
                finishWs(
                    "workspace '$name' already exists (jdx ws info $name to see it; " +
                        "jdx ws add $name <root> to extend it; jdx ws remove $name to replace it)",
                    WsPayload(
                        workspace = name,
                        jars = store.load(name)?.jars ?: emptyList(),
                        message = "workspace '$name' already exists",
                    ),
                    "ws create", json, 1, terminate,
                )
                return
            }
            store.save(definition)
        } catch (e: IOException) {
            val message = corruptOrIoMessage("create", name, e)
            finishWs(
                message,
                WsPayload(workspace = name, message = message),
                "ws create", json, storeExit(e), terminate,
            )
            return
        }
        val jdkWord = if (definition.includeJdk) "included" else "excluded"
        finishWs(
            "workspace '$name' created\n  jars: ${jars.size} root(s)\n  jdk: $jdkWord\n" +
                "next: jdx -w $name members <type>",
            WsPayload(workspace = name, jars = jars, includeJdk = definition.includeJdk, message = "created"),
            "ws create", json, 0, terminate,
        )
    }
}

/** `jdx ws list` — sorted names, active marked; a corrupt file never hides the rest. */
class WsListCommand(
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "list") {
    override fun help(context: Context): String =
        "List named workspaces (the default from jdx ws use is marked). Exits 6 on IO failure."

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        val names: List<String>
        val active: String?
        try {
            names = store.listNames()
            active = store.activeName()
        } catch (e: IOException) {
            val message = "cannot list workspaces: ${e.message}"
            finishWs(message, WsPayload(message = message), "ws list", json, 6, terminate)
            return
        }
        if (names.isEmpty()) {
            finishWs(
                "no workspaces (jdx ws create <name> --jars <path>)",
                WsPayload(message = "no workspaces"),
                "ws list", json, 0, terminate,
            )
            return
        }
        val lines = names.joinToString("\n") { if (it == active) "  $it (default)" else "  $it" }
        finishWs(
            "workspaces (${names.size})\n$lines",
            WsPayload(workspaces = names, message = "${names.size} workspace(s)"),
            "ws list", json, 0, terminate,
        )
    }
}

/** `jdx ws info <name>` — ordered roots and switches, never absolute paths (D-007). */
class WsInfoCommand(
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "info") {
    override fun help(context: Context): String =
        "Show a workspace: ordered roots, JDK switch, whether it is the default. " +
            "Exits 1 when unknown, 4 when its file is corrupt."

    private val name by argument(help = "Workspace name to show.")

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        validateWorkspaceName(name)?.let {
            finishWs(
                "usage error: invalid workspace name '$name': $it",
                WsPayload(workspace = name, message = "invalid workspace name '$name': $it"),
                "ws info", json, 3, terminate,
            )
            return
        }
        val definition: WorkspaceDefinition?
        val active: String?
        try {
            definition = store.load(name)
            active = store.activeName()
        } catch (e: IOException) {
            val message = corruptOrIoMessage("read", name, e)
            finishWs(message, WsPayload(workspace = name, message = message), "ws info", json, storeExit(e), terminate)
            return
        }
        if (definition == null) {
            val hint = didYouMean(name, storeNames())
            finishWs(
                "no such workspace '$name'. $hint(jdx ws list to see all)",
                WsPayload(workspace = name, message = "no such workspace '$name'"),
                "ws info", json, 1, terminate,
            )
            return
        }
        val jarLines = if (definition.jars.isEmpty()) "  jars: (none)" else
            "  jars:\n" + definition.jars.joinToString("\n") { "    $it" }
        val jdkWord = if (definition.includeJdk) "included" else "excluded"
        val defaultWord = if (active == name) "yes (jdx ws use)" else "no"
        finishWs(
            "workspace '$name'\n$jarLines\n  jdk: $jdkWord\n  default: $defaultWord",
            WsPayload(
                workspace = name,
                jars = definition.jars,
                includeJdk = definition.includeJdk,
                active = active == name,
                message = "ok",
            ),
            "ws info", json, 0, terminate,
        )
    }

    private fun storeNames(): List<String> = try {
        store.listNames()
    } catch (e: IOException) {
        emptyList()
    }
}

/** `jdx ws remove <name>` — deletes the file; clears the default when it pointed here. */
class WsRemoveCommand(
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "remove") {
    override fun help(context: Context): String =
        "Delete a named workspace (clears the jdx ws use default when it pointed here). " +
            "Exits 1 when unknown, 6 on IO failure."

    private val name by argument(help = "Workspace name to delete.")

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        validateWorkspaceName(name)?.let {
            finishWs(
                "usage error: invalid workspace name '$name': $it",
                WsPayload(workspace = name, message = "invalid workspace name '$name': $it"),
                "ws remove", json, 3, terminate,
            )
            return
        }
        try {
            if (!store.delete(name)) {
                finishWs(
                    "no such workspace '$name' (jdx ws list to see all)",
                    WsPayload(workspace = name, message = "no such workspace '$name'"),
                    "ws remove", json, 1, terminate,
                )
                return
            }
            if (store.activeName() == name) store.setActive(null)
        } catch (e: IOException) {
            val message = "cannot remove workspace '$name': ${e.message}"
            finishWs(message, WsPayload(workspace = name, message = message), "ws remove", json, 6, terminate)
            return
        }
        finishWs(
            "workspace '$name' removed",
            WsPayload(workspace = name, message = "removed"),
            "ws remove", json, 0, terminate,
        )
    }
}

/** `jdx ws use <name>` / `jdx ws use --clear` — the §13 step-4 default workspace. */
class WsUseCommand(
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "use") {
    override fun help(context: Context): String =
        "Set the default workspace read commands use when neither -w nor JDX_WORKSPACE " +
            "names one (PROPOSAL.md §13). With --clear, unset it. " +
            "Exits 1 when the workspace is unknown, 3 on bad usage."

    private val name by argument(help = "Workspace name to make the default.").optional()

    private val clear by option("--clear", help = "Unset the default workspace.").flag()

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        if (clear) {
            if (name != null) {
                finishWs(
                    "usage error: pass either a workspace name or --clear, not both",
                    WsPayload(message = "pass either a workspace name or --clear, not both"),
                    "ws use", json, 3, terminate,
                )
                return
            }
            try {
                store.setActive(null)
            } catch (e: IOException) {
                val message = "cannot clear the default workspace: ${e.message}"
                finishWs(message, WsPayload(message = message), "ws use", json, 6, terminate)
                return
            }
            finishWs(
                "default workspace cleared",
                WsPayload(message = "cleared"),
                "ws use", json, 0, terminate,
            )
            return
        }
        val selected = name
        if (selected == null) {
            finishWs(
                "usage error: pass a workspace name (or --clear to unset the default)",
                WsPayload(message = "pass a workspace name or --clear"),
                "ws use", json, 3, terminate,
            )
            return
        }
        validateWorkspaceName(selected)?.let {
            finishWs(
                "usage error: invalid workspace name '$selected': $it",
                WsPayload(workspace = selected, message = "invalid workspace name '$selected': $it"),
                "ws use", json, 3, terminate,
            )
            return
        }
        try {
            if (store.load(selected) == null) {
                finishWs(
                    "no such workspace '$selected' (jdx ws list to see all)",
                    WsPayload(workspace = selected, message = "no such workspace '$selected'"),
                    "ws use", json, 1, terminate,
                )
                return
            }
            store.setActive(selected)
        } catch (e: IOException) {
            val message = corruptOrIoMessage("select", selected, e)
            finishWs(message, WsPayload(workspace = selected, message = message), "ws use", json, storeExit(e), terminate)
            return
        }
        finishWs(
            "default workspace is now '$selected'",
            WsPayload(workspace = selected, active = true, message = "selected"),
            "ws use", json, 0, terminate,
        )
    }
}

/** `jdx ws add <name> <root>` — appends one binary root, keeping stored order. */
class WsAddCommand(
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "add") {
    override fun help(context: Context): String =
        "Append one binary root (jar, class dir or glob) to a workspace, keeping order. " +
            "Exits 1 when the workspace is unknown, 3 on bad usage, 4/6 on store failure."

    private val name by argument(help = "Workspace to extend.")

    private val root by argument(help = "Binary root to append (jar, class dir or glob).")

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        validateWorkspaceName(name)?.let {
            finishWs(
                "usage error: invalid workspace name '$name': $it",
                WsPayload(workspace = name, message = "invalid workspace name '$name': $it"),
                "ws add", json, 3, terminate,
            )
            return
        }
        try {
            val current = store.load(name)
            if (current == null) {
                finishWs(
                    "no such workspace '$name' (jdx ws create $name --jars $root to make it)",
                    WsPayload(workspace = name, message = "no such workspace '$name'"),
                    "ws add", json, 1, terminate,
                )
                return
            }
            if (root !in current.jars) {
                store.save(current.copy(jars = current.jars + root))
            }
            val jars = if (root in current.jars) current.jars else current.jars + root
            val note = if (root in current.jars) "(already present)" else ""
            finishWs(
                "workspace '$name' now has ${jars.size} root(s) $note".trim(),
                WsPayload(workspace = name, jars = jars, includeJdk = current.includeJdk, message = "added"),
                "ws add", json, 0, terminate,
            )
        } catch (e: IOException) {
            val message = corruptOrIoMessage("extend", name, e)
            finishWs(message, WsPayload(workspace = name, message = message), "ws add", json, storeExit(e), terminate)
        }
    }
}

private fun didYouMean(missed: String, names: List<String>): String {
    if (names.isEmpty()) return ""
    val hint = dev.jdx.index.workspace.WorkspaceResolver.suggestSimilar(missed, names, cap = 3)
        .joinToString(", ")
    return "Did you mean: $hint. "
}
