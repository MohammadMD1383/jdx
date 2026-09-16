package dev.jdx.cli.commands

import dev.jdx.core.render.ErrorResult
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceResolver
import dev.jdx.index.workspace.WorkspaceStore
import kotlin.system.exitProcess

/**
 * Shared plumbing for the three read commands (T-011): `show`, `members`, `outline`.
 *
 * Thin by rule (D-004): flag *values* are mapped to [JdxService] inputs here, and flag
 * *combinations* that make no sense are rejected with an exit-3 usage error before any
 * IO happens. Everything about *behaviour* — resolution, filtering, rendering — lives
 * in `core`/`index` behind [JdxService.ServiceOutcome], which already carries its own
 * exit code and both renderings (D-007).
 *
 * Non-zero outcomes terminate the process via [terminate] (default
 * [exitProcess], mirroring `DoctorCommand`). The no-op choice is deliberate:
 * clikt-core's own `exitProcess` hook defaults to `{ }` — the real one lives in
 * the mordant flavor we excluded (L-011) — so throwing `ProgramResult` alone
 * would exit 0 and silently break the D-015 contract. Tests inject a fake that
 * throws instead of dying with the host JVM.
 */
internal object ReadCommandSupport {

    /** Builds the workspace roots both commands read: repeatable `--jars`, JDK unless dropped. */
    internal fun rootsOf(jars: List<String>, noJdk: Boolean): JdxService.RootsSpec =
        JdxService.RootsSpec(jarSpecs = jars, includeJdk = !noJdk)

    /**
     * Resolves the roots one read query opens (PROPOSAL.md §13, T-015): explicit `--jars`
     * merge in front of the selected workspace (`-w` beats `JDX_WORKSPACE` beats
     * `jdx ws use`), the JDK stays unless `--no-jdk` or the workspace switches it off.
     *
     * [store] and [getenv] are injectable so command tests resolve without touching the
     * real home directory or the process environment.
     */
    internal fun resolveRoots(
        jars: List<String>,
        noJdk: Boolean,
        workspace: String?,
        store: WorkspaceStore = FileWorkspaceStore.system(),
        getenv: (String) -> String? = System::getenv,
    ): RootsOrFailure {
        val outcome = WorkspaceResolver.resolve(
            explicitJars = jars,
            explicitNoJdk = noJdk,
            flagWorkspace = workspace,
            envWorkspace = try {
                getenv("JDX_WORKSPACE")
            } catch (e: SecurityException) {
                null
            },
            activeWorkspace = try {
                store.activeName()
            } catch (e: Exception) {
                null
            },
            loadWorkspace = store::load,
            listNames = {
                try {
                    store.listNames()
                } catch (e: Exception) {
                    emptyList()
                }
            },
        )
        return when (outcome) {
            is WorkspaceResolver.Result.success ->
                RootsOrFailure.Ready(JdxService.RootsSpec.fromResolved(outcome.value))
            is WorkspaceResolver.Result.failure ->
                RootsOrFailure.Failed(
                    JdxService.ServiceOutcome.Failure(
                        ErrorResult.generic("", exitCode = 4, message = outcome.error.message),
                    ),
                )
        }
    }

    /** The two ways root resolution ends: roots to query with, or an exit-4 outcome to render. */
    internal sealed interface RootsOrFailure {
        /** Resolution succeeded — query with these roots. */
        data class Ready(val roots: JdxService.RootsSpec) : RootsOrFailure

        /** Resolution failed (unknown/corrupt workspace) — render this instead of querying. */
        data class Failed(val outcome: JdxService.ServiceOutcome.Failure) : RootsOrFailure
    }

    /** TTY-ness for the renderers (D-028): color only on a real console, never when piped. */
    internal fun useColor(noColor: Boolean): Boolean =
        !noColor && System.console() != null

    /** Maps `--access` to the resolver's visibility set; `null` keeps the service default. */
    internal fun accessOf(access: String?): Set<dev.jdx.core.model.Visibility>? = when (access) {
        null -> null
        "all" -> setOf(
            dev.jdx.core.model.Visibility.PUBLIC,
            dev.jdx.core.model.Visibility.PROTECTED,
            dev.jdx.core.model.Visibility.PACKAGE_PRIVATE,
            dev.jdx.core.model.Visibility.PRIVATE,
        )
        "public" -> setOf(dev.jdx.core.model.Visibility.PUBLIC)
        "protected" -> setOf(dev.jdx.core.model.Visibility.PROTECTED)
        "package" -> setOf(dev.jdx.core.model.Visibility.PACKAGE_PRIVATE)
        "private" -> setOf(dev.jdx.core.model.Visibility.PRIVATE)
        else -> null
    }

    /** Maps `--kind` to the service filter; Clikt's `choice()` guarantees the input range. */
    internal fun kindOf(kind: String): JdxService.KindFilter = when (kind) {
        "method" -> JdxService.KindFilter.METHOD
        "field" -> JdxService.KindFilter.FIELD
        "ctor" -> JdxService.KindFilter.CTOR
        else -> JdxService.KindFilter.ALL
    }

    /**
     * Validates the flag combinations that reach the service. Returns the usage error
     * message, or `null` when the flags are coherent. Deferred flags fail here with the
     * owning task named, per the T-011 acceptance rule.
     */
    internal fun validateMemberFlags(
        static: Boolean,
        instance: Boolean,
        grep: String?,
        withDoc: Boolean,
        sort: String,
    ): String? {
        if (static && instance) {
            return "usage error: --static and --instance are mutually exclusive"
        }
        if (grep != null) {
            try {
                Regex(grep)
            } catch (e: java.util.regex.PatternSyntaxException) {
                return "usage error: invalid --grep regex '$grep': ${e.message}"
            }
        }
        if (withDoc) {
            return "usage error: --with-doc is not yet implemented (T-025: javadoc/KDoc rendering)"
        }
        if (sort != "kind") {
            return "usage error: --sort $sort is not yet implemented (T-062: member sort orders)"
        }
        return null
    }

    /** Prints the outcome in the requested rendering and terminates on non-zero exit. */
    internal fun finish(
        outcome: JdxService.ServiceOutcome,
        command: String,
        json: Boolean,
        noColor: Boolean,
        terminate: (Int) -> Nothing = ::exitProcess,
    ) {
        if (json) {
            println(outcome.toJson(command))
        } else {
            println(outcome.renderText(useColor(noColor)))
        }
        if (outcome.exitCode != 0) terminate(outcome.exitCode)
    }
}

/** Query behind `members`/`outline`, injectable so command tests run without IO (T-011). */
internal typealias MemberQuery = (
    ref: String,
    roots: JdxService.RootsSpec,
    filters: JdxService.MemberFilters,
    declaredOnly: Boolean,
    includeSynthetic: Boolean,
    maxMembers: Int,
) -> JdxService.ServiceOutcome

/** Query behind `show`, injectable so command tests run without IO (T-011). */
internal typealias ShowQuery = (
    ref: String,
    roots: JdxService.RootsSpec,
) -> JdxService.ServiceOutcome

internal fun defaultMemberQuery(
    ref: String,
    roots: JdxService.RootsSpec,
    filters: JdxService.MemberFilters,
    declaredOnly: Boolean,
    includeSynthetic: Boolean,
    maxMembers: Int,
): JdxService.ServiceOutcome =
    JdxService.members(ref, roots, filters, declaredOnly, includeSynthetic, maxMembers)

internal fun defaultShowQuery(ref: String, roots: JdxService.RootsSpec): JdxService.ServiceOutcome =
    JdxService.show(ref, roots)
